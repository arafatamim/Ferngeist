package registry

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"path/filepath"
	goruntime "runtime"
	"sort"
	"strings"
	"sync"
	"time"
)

const DefaultURL = "https://cdn.agentclientprotocol.com/registry/v1/latest/registry.json"

// AgentEntry is the normalized subset of the ACP registry the helper cares
// about: identity, distribution kinds, and the current platform's binary data.
type AgentEntry struct {
	ID                string
	Name              string
	Version           string
	Repository        string
	Website           string
	DistributionKinds []string
	BinaryTargets     map[string]BinaryTarget
	CurrentBinary     *BinaryTarget
	NpxPackage        string
}

type BinaryTarget struct {
	Platform    string
	ArchiveURL  string
	Command     string
	CommandName string
	Args        []string
}

type Snapshot struct {
	Version   string
	Agents    map[string]AgentEntry
	FetchedAt time.Time
}

type Status struct {
	URL           string    `json:"url"`
	State         string    `json:"state"`
	Version       string    `json:"version,omitempty"`
	AgentCount    int       `json:"agentCount"`
	LastFetchedAt time.Time `json:"lastFetchedAt"`
	LastError     string    `json:"lastError,omitempty"`
}

type Client struct {
	url        string
	httpClient *http.Client
	ttl        time.Duration
	now        func() time.Time

	mu        sync.Mutex
	cached    Snapshot
	cachedAt  time.Time
	lastError string
}

type registryDocument struct {
	Version string                  `json:"version"`
	Agents  []registryAgentDocument `json:"agents"`
}

type registryAgentDocument struct {
	ID           string                     `json:"id"`
	Name         string                     `json:"name"`
	Version      string                     `json:"version"`
	Repository   string                     `json:"repository"`
	Website      string                     `json:"website"`
	Distribution map[string]json.RawMessage `json:"distribution"`
}

func New(url string, ttl time.Duration) *Client {
	if url == "" {
		url = DefaultURL
	}
	if ttl <= 0 {
		ttl = 6 * time.Hour
	}

	return &Client{
		url: url,
		httpClient: &http.Client{
			Timeout: 10 * time.Second,
		},
		ttl: ttl,
		now: time.Now,
	}
}

// Snapshot returns the cached registry snapshot when it is still fresh and only
// reaches over the network on cache miss or TTL expiry.
func (c *Client) Snapshot(ctx context.Context) (Snapshot, error) {
	c.mu.Lock()
	if c.cachedAt.IsZero() || c.now().After(c.cachedAt.Add(c.ttl)) {
		c.mu.Unlock()
		return c.refresh(ctx)
	}
	snapshot := c.cached
	c.mu.Unlock()
	return snapshot, nil
}

// Status exposes fetch health for diagnostics without forcing a refresh.
func (c *Client) Status() Status {
	c.mu.Lock()
	defer c.mu.Unlock()

	status := Status{
		URL: c.url,
	}
	if c.cachedAt.IsZero() && c.lastError == "" {
		status.State = "idle"
		return status
	}
	if !c.cachedAt.IsZero() {
		status.Version = c.cached.Version
		status.AgentCount = len(c.cached.Agents)
		status.LastFetchedAt = c.cachedAt
		if c.now().After(c.cachedAt.Add(c.ttl)) {
			status.State = "stale"
		} else {
			status.State = "ready"
		}
	}
	if c.lastError != "" {
		status.LastError = c.lastError
		if status.State == "" || status.State == "idle" {
			status.State = "error"
		}
	}
	if status.State == "" {
		status.State = "idle"
	}
	return status
}

// refresh performs the actual registry fetch and normalization. The rest of the
// helper only consumes the smaller Snapshot shape produced here.
func (c *Client) refresh(ctx context.Context) (Snapshot, error) {
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, c.url, nil)
	if err != nil {
		c.recordError(err)
		return Snapshot{}, err
	}

	response, err := c.httpClient.Do(request)
	if err != nil {
		c.recordError(err)
		return Snapshot{}, err
	}
	defer response.Body.Close()

	if response.StatusCode != http.StatusOK {
		err := errors.New("registry returned non-200 status")
		c.recordError(err)
		return Snapshot{}, err
	}

	var document registryDocument
	if err := json.NewDecoder(response.Body).Decode(&document); err != nil {
		c.recordError(err)
		return Snapshot{}, err
	}

	snapshot := Snapshot{
		Version:   document.Version,
		Agents:    make(map[string]AgentEntry, len(document.Agents)),
		FetchedAt: c.now().UTC(),
	}
	for _, agent := range document.Agents {
		distributionKinds := make([]string, 0, len(agent.Distribution))
		for kind := range agent.Distribution {
			distributionKinds = append(distributionKinds, kind)
		}
		sort.Strings(distributionKinds)
		binaryTargets := parseBinaryTargets(agent.Distribution["binary"])
		npxPackage := parseNpxPackage(agent.Distribution["npx"])

		snapshot.Agents[agent.ID] = AgentEntry{
			ID:                agent.ID,
			Name:              agent.Name,
			Version:           agent.Version,
			Repository:        agent.Repository,
			Website:           agent.Website,
			DistributionKinds: distributionKinds,
			BinaryTargets:     binaryTargets,
			CurrentBinary:     currentBinaryTarget(binaryTargets),
			NpxPackage:        npxPackage,
		}
	}

	c.mu.Lock()
	c.cached = snapshot
	c.cachedAt = snapshot.FetchedAt
	c.lastError = ""
	c.mu.Unlock()

	return snapshot, nil
}

func (c *Client) recordError(err error) {
	if err == nil {
		return
	}
	c.mu.Lock()
	c.lastError = err.Error()
	c.mu.Unlock()
}

// parseBinaryTargets tolerates partial or unknown registry content by returning
// only the helper-relevant binary launch metadata.
func parseBinaryTargets(raw json.RawMessage) map[string]BinaryTarget {
	if len(raw) == 0 {
		return nil
	}

	var document map[string]struct {
		Archive string   `json:"archive"`
		Cmd     string   `json:"cmd"`
		Args    []string `json:"args"`
	}
	if err := json.Unmarshal(raw, &document); err != nil {
		return nil
	}

	targets := make(map[string]BinaryTarget, len(document))
	for platform, target := range document {
		commandName := normalizeBinaryCommandName(target.Cmd)
		targets[platform] = BinaryTarget{
			Platform:    platform,
			ArchiveURL:  target.Archive,
			Command:     target.Cmd,
			CommandName: commandName,
			Args:        append([]string(nil), target.Args...),
		}
	}
	return targets
}

func parseNpxPackage(raw json.RawMessage) string {
	if len(raw) == 0 {
		return ""
	}

	var document struct {
		Package string `json:"package"`
	}
	if err := json.Unmarshal(raw, &document); err != nil {
		return ""
	}
	return strings.TrimSpace(document.Package)
}

func currentBinaryTarget(targets map[string]BinaryTarget) *BinaryTarget {
	if len(targets) == 0 {
		return nil
	}
	target, ok := targets[currentPlatformKey()]
	if !ok {
		return nil
	}
	copyTarget := target
	return &copyTarget
}

func currentPlatformKey() string {
	return goruntime.GOOS + "-" + normalizeArchitecture(goruntime.GOARCH)
}

func normalizeArchitecture(arch string) string {
	switch arch {
	case "amd64":
		return "x86_64"
	case "arm64":
		return "aarch64"
	default:
		return arch
	}
}

func normalizeBinaryCommandName(command string) string {
	base := filepath.Base(strings.ReplaceAll(strings.TrimSpace(command), "\\", "/"))
	if strings.HasSuffix(strings.ToLower(base), ".exe") {
		base = strings.TrimSuffix(base, filepath.Ext(base))
	}
	return base
}
