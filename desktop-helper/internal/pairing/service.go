package pairing

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"fmt"
	"log/slog"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/tamimarafat/ferngeist/desktop-helper/internal/storage"
)

const (
	defaultChallengeTTL = 2 * time.Minute
	defaultArmTTL       = 2 * time.Minute
	defaultTokenTTL     = 7 * 24 * time.Hour
	challengeHistoryTTL = 10 * time.Minute
	codeLength          = 6
)

var (
	ErrChallengeNotFound  = errors.New("pairing challenge not found")
	ErrChallengeExpired   = errors.New("pairing challenge expired")
	ErrChallengeAmbiguous = errors.New("pairing challenge is ambiguous")
	ErrCodeMismatch       = errors.New("pairing code mismatch")
	ErrInvalidDeviceName  = errors.New("device name is required")
	ErrDeviceNotFound     = errors.New("paired device not found")
	ErrCredentialMissing  = errors.New("helper credential missing")
	ErrCredentialInvalid  = errors.New("helper credential invalid")
	ErrCredentialExpired  = errors.New("helper credential expired")
	ErrCredentialScope    = errors.New("helper credential does not allow this operation")
	ErrPairingNotArmed    = errors.New("pairing requires local approval")
)

const (
	ScopeRead              = "helper.read"
	ScopeControl           = "helper.control"
	ScopeDiagnosticsExport = "helper.diagnostics.export"
	ScopeRuntimeRestartEnv = "helper.runtime.restart_env"
)

type ChallengeState string

const (
	ChallengeStateActive    ChallengeState = "active"
	ChallengeStateCompleted ChallengeState = "completed"
	ChallengeStateCancelled ChallengeState = "cancelled"
	ChallengeStateExpired   ChallengeState = "expired"
)

type Challenge struct {
	ID        string    `json:"id"`
	Code      string    `json:"code"`
	ExpiresAt time.Time `json:"expiresAt"`
}

type Credential struct {
	DeviceID       string    `json:"deviceId"`
	DeviceName     string    `json:"deviceName"`
	Token          string    `json:"token"`
	TokenHash      string    `json:"-"`
	ExpiresAt      time.Time `json:"expiresAt"`
	Scopes         []string  `json:"scopes,omitempty"`
	ProofPublicKey string    `json:"proofPublicKey,omitempty"`
}

type CompletedDevice struct {
	DeviceID   string    `json:"deviceId"`
	DeviceName string    `json:"deviceName"`
	ExpiresAt  time.Time `json:"expiresAt"`
}

type ChallengeStatus struct {
	ID              string           `json:"id"`
	Code            string           `json:"code"`
	ExpiresAt       time.Time        `json:"expiresAt"`
	State           ChallengeState   `json:"state"`
	CompletedDevice *CompletedDevice `json:"completedDevice,omitempty"`
}

type challengeRecord struct {
	challenge       Challenge
	state           ChallengeState
	completedDevice *CompletedDevice
	stateChangedAt  time.Time
}

// Service manages helper-local trust bootstrap. Pairing challenges are
// short-lived and in-memory; issued device credentials can be reloaded from
// SQLite so the helper survives restarts.
type Service struct {
	logger      *slog.Logger
	mu          sync.Mutex
	now         func() time.Time
	armTTL      time.Duration
	tokenTTL    time.Duration
	baseScopes  []string
	activeID    string
	armedUntil  time.Time
	challenges  map[string]challengeRecord
	credentials map[string]Credential
	store       *storage.SQLiteStore
}

type Options struct {
	ArmTTL                 time.Duration
	CredentialTTL          time.Duration
	AllowDiagnosticsExport bool
	AllowRuntimeRestartEnv bool
}

func NewService(logger *slog.Logger, store *storage.SQLiteStore) *Service {
	return NewServiceWithOptions(logger, store, Options{})
}

func NewServiceWithOptions(logger *slog.Logger, store *storage.SQLiteStore, options Options) *Service {
	armTTL := options.ArmTTL
	if armTTL <= 0 {
		armTTL = defaultArmTTL
	}
	tokenTTL := options.CredentialTTL
	if tokenTTL <= 0 {
		tokenTTL = defaultTokenTTL
	}
	service := &Service{
		logger:      logger.With("component", "pairing"),
		now:         time.Now,
		armTTL:      armTTL,
		tokenTTL:    tokenTTL,
		baseScopes:  defaultCredentialScopes(options.AllowDiagnosticsExport, options.AllowRuntimeRestartEnv),
		challenges:  make(map[string]challengeRecord),
		credentials: make(map[string]Credential),
		store:       store,
	}
	service.loadPersistedCredentials()
	return service
}

func (s *Service) StartPairing() (Challenge, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	now := s.now().UTC()
	s.pruneExpiredLocked(now)
	if status, ok := s.activeChallengeLocked(); ok {
		return status.challenge, nil
	}
	if !s.isArmedLocked(now) {
		return Challenge{}, ErrPairingNotArmed
	}

	challenge := Challenge{
		ID:        randomToken(18),
		Code:      randomCode(codeLength),
		ExpiresAt: now.Add(defaultChallengeTTL),
	}
	s.activeID = challenge.ID
	s.challenges[challenge.ID] = challengeRecord{
		challenge:      challenge,
		state:          ChallengeStateActive,
		stateChangedAt: now,
	}
	return challenge, nil
}

// StartPairingWithLocalApproval opens a short pairing window and then starts
// (or returns) the active challenge. Intended for trusted local control paths.
func (s *Service) StartPairingWithLocalApproval() (Challenge, error) {
	s.mu.Lock()
	now := s.now().UTC()
	s.armedUntil = now.Add(s.armTTL)
	s.mu.Unlock()
	return s.StartPairing()
}

func (s *Service) ActiveChallenge() (ChallengeStatus, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()

	s.pruneExpiredLocked(s.now().UTC())
	record, ok := s.activeChallengeLocked()
	if !ok {
		return ChallengeStatus{}, false
	}
	return record.toStatus(), true
}

func (s *Service) GetChallengeStatus(challengeID string) (ChallengeStatus, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	s.pruneExpiredLocked(s.now().UTC())
	record, ok := s.challenges[challengeID]
	if !ok {
		return ChallengeStatus{}, ErrChallengeNotFound
	}
	return record.toStatus(), nil
}

func (s *Service) CancelChallenge(challengeID string) (ChallengeStatus, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	now := s.now().UTC()
	s.pruneExpiredLocked(now)

	if challengeID == "" {
		challengeID = s.activeID
	}
	if challengeID == "" {
		return ChallengeStatus{}, ErrChallengeNotFound
	}

	record, ok := s.challenges[challengeID]
	if !ok {
		return ChallengeStatus{}, ErrChallengeNotFound
	}
	if record.state == ChallengeStateActive {
		record.state = ChallengeStateCancelled
		record.stateChangedAt = now
		s.challenges[challengeID] = record
		if s.activeID == challengeID {
			s.activeID = ""
		}
	}
	return record.toStatus(), nil
}

// CompletePairing exchanges a valid short-lived challenge for a longer-lived
// device credential. Clients may provide either a challenge ID plus code, or a
// code alone when the helper has displayed a short code separately from the QR
// payload. Code-only completion succeeds only when that code resolves to a
// single active challenge.
func (s *Service) CompletePairing(challengeID, code, deviceName string) (Credential, error) {
	return s.CompletePairingWithProofKey(challengeID, code, deviceName, "")
}

func (s *Service) CompletePairingWithProofKey(challengeID, code, deviceName, proofPublicKey string) (Credential, error) {
	if deviceName == "" {
		return Credential{}, ErrInvalidDeviceName
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	now := s.now().UTC()
	s.pruneExpiredCredentialsLocked(now)

	challengeID, challenge, err := s.resolveChallengeLocked(challengeID, code, now)
	if err != nil {
		return Credential{}, err
	}
	if code != challenge.challenge.Code {
		return Credential{}, ErrCodeMismatch
	}

	credential := Credential{
		DeviceID:       randomToken(18),
		DeviceName:     deviceName,
		Token:          randomToken(32),
		ExpiresAt:      now.Add(s.tokenTTL),
		Scopes:         append([]string(nil), s.baseScopes...),
		ProofPublicKey: proofPublicKey,
	}
	credential.TokenHash = hashCredentialToken(credential.Token)
	s.credentials[credential.DeviceID] = credential
	if s.store != nil {
		if err := s.store.SavePairing(context.Background(), storage.PairingRecord{
			DeviceID:       credential.DeviceID,
			DeviceName:     credential.DeviceName,
			Token:          credential.TokenHash,
			ExpiresAt:      credential.ExpiresAt,
			Scopes:         credential.Scopes,
			ProofPublicKey: credential.ProofPublicKey,
		}); err != nil {
			s.logger.Error("persist pairing failed", "error", err)
		}
	}
	challenge.state = ChallengeStateCompleted
	challenge.stateChangedAt = now
	challenge.completedDevice = &CompletedDevice{
		DeviceID:   credential.DeviceID,
		DeviceName: credential.DeviceName,
		ExpiresAt:  credential.ExpiresAt,
	}
	s.challenges[challengeID] = challenge
	if s.activeID == challengeID {
		s.activeID = ""
	}
	s.armedUntil = time.Time{}
	return credential, nil
}

func (s *Service) resolveChallengeLocked(challengeID, code string, now time.Time) (string, challengeRecord, error) {
	if challengeID != "" {
		challenge, ok := s.challenges[challengeID]
		if !ok {
			return "", challengeRecord{}, ErrChallengeNotFound
		}
		if challenge.state != ChallengeStateActive {
			if challenge.state == ChallengeStateExpired {
				return "", challengeRecord{}, ErrChallengeExpired
			}
			return "", challengeRecord{}, ErrChallengeNotFound
		}
		if now.After(challenge.challenge.ExpiresAt) {
			s.expireChallengeLocked(challengeID, challenge)
			return "", challengeRecord{}, ErrChallengeExpired
		}
		return challengeID, challenge, nil
	}

	record, ok := s.activeChallengeLocked()
	if !ok {
		return "", challengeRecord{}, ErrChallengeNotFound
	}
	if now.After(record.challenge.ExpiresAt) {
		s.expireChallengeLocked(record.challenge.ID, record)
		return "", challengeRecord{}, ErrChallengeExpired
	}
	if record.challenge.Code != code {
		return record.challenge.ID, record, nil
	}
	return record.challenge.ID, record, nil
}

func (s *Service) ActiveDeviceCount() int {
	s.mu.Lock()
	defer s.mu.Unlock()

	s.pruneExpiredCredentialsLocked(s.now().UTC())
	return len(s.credentials)
}

// ValidateCredential is a simple token lookup because helper-issued device
// credentials are already random opaque tokens scoped to this daemon.
func (s *Service) ValidateCredential(token string) (Credential, error) {
	if token == "" {
		return Credential{}, ErrCredentialMissing
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	now := s.now().UTC()

	for id, credential := range s.credentials {
		if credentialMatchesToken(credential, token) {
			if now.After(credential.ExpiresAt) {
				s.deleteCredentialLocked(id)
				return Credential{}, ErrCredentialExpired
			}
			return credential, nil
		}
	}

	s.pruneExpiredCredentialsLocked(now)
	return Credential{}, ErrCredentialInvalid
}

func (s *Service) RefreshCredential(token string) (Credential, error) {
	if token == "" {
		return Credential{}, ErrCredentialMissing
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	now := s.now().UTC()
	for id, credential := range s.credentials {
		if !credentialMatchesToken(credential, token) {
			continue
		}
		if now.After(credential.ExpiresAt) {
			s.deleteCredentialLocked(id)
			return Credential{}, ErrCredentialExpired
		}
		credential.Token = randomToken(32)
		credential.TokenHash = hashCredentialToken(credential.Token)
		credential.ExpiresAt = now.Add(s.tokenTTL)
		s.credentials[id] = credential
		if s.store != nil {
			if err := s.store.SavePairing(context.Background(), storage.PairingRecord{
				DeviceID:       credential.DeviceID,
				DeviceName:     credential.DeviceName,
				Token:          credential.TokenHash,
				ExpiresAt:      credential.ExpiresAt,
				Scopes:         credential.Scopes,
				ProofPublicKey: credential.ProofPublicKey,
			}); err != nil {
				s.logger.Error("persist refreshed pairing failed", "error", err)
			}
		}
		return credential, nil
	}

	s.pruneExpiredCredentialsLocked(now)
	return Credential{}, ErrCredentialInvalid
}

func (c Credential) HasScope(scope string) bool {
	for _, existing := range c.Scopes {
		if existing == scope {
			return true
		}
	}
	return false
}

func (c Credential) RequireScope(scope string) error {
	if c.HasScope(scope) {
		return nil
	}
	return ErrCredentialScope
}

func (s *Service) ListDevices() []Credential {
	s.mu.Lock()
	defer s.mu.Unlock()

	s.pruneExpiredCredentialsLocked(s.now().UTC())
	devices := make([]Credential, 0, len(s.credentials))
	for _, credential := range s.credentials {
		devices = append(devices, credential)
	}
	sort.Slice(devices, func(i, j int) bool {
		if devices[i].DeviceName == devices[j].DeviceName {
			return devices[i].DeviceID < devices[j].DeviceID
		}
		return devices[i].DeviceName < devices[j].DeviceName
	})
	return devices
}

func (s *Service) RevokeDevice(deviceID string) (Credential, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	credential, ok := s.credentials[deviceID]
	if !ok {
		return Credential{}, ErrDeviceNotFound
	}
	s.deleteCredentialLocked(deviceID)
	return credential, nil
}

func (s *Service) pruneExpiredLocked(now time.Time) {
	for id, record := range s.challenges {
		switch record.state {
		case ChallengeStateActive:
			if now.After(record.challenge.ExpiresAt) {
				s.expireChallengeLocked(id, record)
			}
		default:
			if now.Sub(record.stateChangedAt) > challengeHistoryTTL {
				delete(s.challenges, id)
			}
		}
	}
	s.pruneExpiredCredentialsLocked(now)
}

func (s *Service) pruneExpiredCredentialsLocked(now time.Time) {
	for id, credential := range s.credentials {
		if now.After(credential.ExpiresAt) {
			s.deleteCredentialLocked(id)
		}
	}
}

func (s *Service) deleteCredentialLocked(deviceID string) {
	delete(s.credentials, deviceID)
	if s.store == nil {
		return
	}
	if err := s.store.DeletePairing(context.Background(), deviceID); err != nil && !errors.Is(err, storage.ErrNotFound) {
		s.logger.Error("delete pairing failed", "device_id", deviceID, "error", err)
	}
}

func (s *Service) activeChallengeLocked() (challengeRecord, bool) {
	if s.activeID == "" {
		return challengeRecord{}, false
	}
	record, ok := s.challenges[s.activeID]
	if !ok || record.state != ChallengeStateActive {
		s.activeID = ""
		return challengeRecord{}, false
	}
	return record, true
}

func (s *Service) isArmedLocked(now time.Time) bool {
	if s.armedUntil.IsZero() {
		return false
	}
	if now.After(s.armedUntil) {
		s.armedUntil = time.Time{}
		return false
	}
	return true
}

func (s *Service) expireChallengeLocked(challengeID string, record challengeRecord) {
	record.state = ChallengeStateExpired
	record.stateChangedAt = record.challenge.ExpiresAt
	s.challenges[challengeID] = record
	if s.activeID == challengeID {
		s.activeID = ""
	}
}

func (r challengeRecord) toStatus() ChallengeStatus {
	return ChallengeStatus{
		ID:              r.challenge.ID,
		Code:            r.challenge.Code,
		ExpiresAt:       r.challenge.ExpiresAt,
		State:           r.state,
		CompletedDevice: r.completedDevice,
	}
}

// loadPersistedCredentials restores still-valid helper credentials so paired
// devices are not forced to re-pair after every daemon restart.
func (s *Service) loadPersistedCredentials() {
	if s.store == nil {
		return
	}

	records, err := s.store.ListPairings(context.Background())
	if err != nil {
		s.logger.Error("load persisted pairings failed", "error", err)
		return
	}

	now := s.now().UTC()
	for _, record := range records {
		if now.After(record.ExpiresAt) {
			if err := s.store.DeletePairing(context.Background(), record.DeviceID); err != nil && !errors.Is(err, storage.ErrNotFound) {
				s.logger.Error("delete expired pairing failed", "device_id", record.DeviceID, "error", err)
			}
			continue
		}
		s.credentials[record.DeviceID] = Credential{
			DeviceID:       record.DeviceID,
			DeviceName:     record.DeviceName,
			TokenHash:      storedCredentialHash(record.Token),
			ExpiresAt:      record.ExpiresAt,
			Scopes:         fallbackScopes(record.Scopes, s.baseScopes),
			ProofPublicKey: record.ProofPublicKey,
		}
		if !isHashedCredentialToken(record.Token) && s.store != nil {
			if err := s.store.SavePairing(context.Background(), storage.PairingRecord{
				DeviceID:       record.DeviceID,
				DeviceName:     record.DeviceName,
				Token:          storedCredentialHash(record.Token),
				ExpiresAt:      record.ExpiresAt,
				Scopes:         fallbackScopes(record.Scopes, s.baseScopes),
				ProofPublicKey: record.ProofPublicKey,
			}); err != nil {
				s.logger.Error("upgrade pairing token hash failed", "device_id", record.DeviceID, "error", err)
			}
		}
	}
}

func defaultCredentialScopes(allowDiagnosticsExport, allowRuntimeRestartEnv bool) []string {
	scopes := []string{ScopeRead, ScopeControl}
	if allowDiagnosticsExport {
		scopes = append(scopes, ScopeDiagnosticsExport)
	}
	if allowRuntimeRestartEnv {
		scopes = append(scopes, ScopeRuntimeRestartEnv)
	}
	return scopes
}

func fallbackScopes(scopes []string, fallback []string) []string {
	if len(scopes) > 0 {
		return append([]string(nil), scopes...)
	}
	return append([]string(nil), fallback...)
}

const credentialHashPrefix = "sha256:"

func credentialMatchesToken(credential Credential, token string) bool {
	if token == "" {
		return false
	}
	if credential.TokenHash != "" {
		return credential.TokenHash == hashCredentialToken(token)
	}
	return credential.Token == token
}

func hashCredentialToken(token string) string {
	digest := sha256.Sum256([]byte(token))
	return credentialHashPrefix + base64.RawURLEncoding.EncodeToString(digest[:])
}

func isHashedCredentialToken(value string) bool {
	return strings.HasPrefix(strings.TrimSpace(value), credentialHashPrefix)
}

func storedCredentialHash(stored string) string {
	if isHashedCredentialToken(stored) {
		return strings.TrimSpace(stored)
	}
	return hashCredentialToken(stored)
}

func randomToken(byteLen int) string {
	buf := make([]byte, byteLen)
	if _, err := rand.Read(buf); err != nil {
		panic(fmt.Errorf("pairing token generation failed: %w", err))
	}
	return base64.RawURLEncoding.EncodeToString(buf)
}

func randomCode(length int) string {
	if length <= 0 {
		return ""
	}
	buf := make([]byte, length)
	if _, err := rand.Read(buf); err != nil {
		panic(fmt.Errorf("pairing code generation failed: %w", err))
	}

	for i := range buf {
		buf[i] = '0' + (buf[i] % 10)
	}
	return string(buf)
}
