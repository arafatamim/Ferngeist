package adminclient

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/tamimarafat/ferngeist/desktop-helper/internal/config"
)

type Client struct {
	baseURL    string
	httpClient *http.Client
}

type PairingStatus struct {
	ChallengeID              string    `json:"challengeId"`
	Code                     string    `json:"code"`
	ExpiresAt                time.Time `json:"expiresAt"`
	State                    string    `json:"state"`
	Scheme                   string    `json:"scheme,omitempty"`
	Host                     string    `json:"host,omitempty"`
	Payload                  string    `json:"payload,omitempty"`
	CompletedDevice          string    `json:"completedDevice,omitempty"`
	CompletedDeviceID        string    `json:"completedDeviceId,omitempty"`
	CompletedDeviceExpiresAt time.Time `json:"completedDeviceExpiresAt,omitempty"`
}

type DaemonStatus struct {
	Name              string            `json:"name"`
	Version           string            `json:"version"`
	ListenAddr        string            `json:"listenAddr"`
	AdminListenAddr   string            `json:"adminListenAddr"`
	LANEnabled        bool              `json:"lanEnabled"`
	PairedDeviceCount int               `json:"pairedDeviceCount"`
	Remote            RemoteStatus      `json:"remote"`
	PairingTarget     PairingTargetInfo `json:"pairingTarget"`
	ActivePairing     *PairingStatus    `json:"activePairing,omitempty"`
	UptimeSeconds     int64             `json:"uptimeSeconds"`
}

type RemoteStatus struct {
	Configured bool   `json:"configured"`
	Mode       string `json:"mode,omitempty"`
	Scope      string `json:"scope,omitempty"`
	Healthy    bool   `json:"healthy"`
	Warning    string `json:"warning,omitempty"`
	PublicURL  string `json:"publicUrl,omitempty"`
}

type PairingTargetInfo struct {
	Reachable bool   `json:"reachable"`
	Scheme    string `json:"scheme,omitempty"`
	Host      string `json:"host,omitempty"`
	Error     string `json:"error,omitempty"`
}

type Device struct {
	DeviceID   string    `json:"deviceId"`
	DeviceName string    `json:"deviceName"`
	ExpiresAt  time.Time `json:"expiresAt"`
}

type devicesResponse struct {
	Devices []Device `json:"devices"`
}

type errorResponse struct {
	Error string `json:"error"`
}

func New(cfg config.Config) *Client {
	return &Client{
		baseURL: strings.TrimRight("http://"+cfg.AdminListenAddr, "/"),
		httpClient: &http.Client{
			Timeout: 10 * time.Second,
		},
	}
}

func (c *Client) StartPairing(ctx context.Context) (PairingStatus, error) {
	return doJSON[PairingStatus](c, ctx, http.MethodPost, "/admin/v1/pairings/start", nil)
}

func (c *Client) Status(ctx context.Context) (DaemonStatus, error) {
	return doJSON[DaemonStatus](c, ctx, http.MethodGet, "/admin/v1/status", nil)
}

func (c *Client) GetPairing(ctx context.Context, challengeID string) (PairingStatus, error) {
	return doJSON[PairingStatus](c, ctx, http.MethodGet, "/admin/v1/pairings/"+challengeID, nil)
}

func (c *Client) CancelPairing(ctx context.Context, challengeID string) (PairingStatus, error) {
	return doJSON[PairingStatus](c, ctx, http.MethodDelete, "/admin/v1/pairings/"+challengeID, nil)
}

func (c *Client) ListDevices(ctx context.Context) ([]Device, error) {
	response, err := doJSON[devicesResponse](c, ctx, http.MethodGet, "/admin/v1/devices", nil)
	if err != nil {
		return nil, err
	}
	return response.Devices, nil
}

func (c *Client) RevokeDevice(ctx context.Context, deviceID string) (Device, error) {
	return doJSON[Device](c, ctx, http.MethodDelete, "/admin/v1/devices/"+deviceID, nil)
}

func doJSON[T any](c *Client, ctx context.Context, method, path string, body any) (T, error) {
	var zero T

	var requestBody []byte
	if body != nil {
		encoded, err := json.Marshal(body)
		if err != nil {
			return zero, err
		}
		requestBody = encoded
	}

	req, err := http.NewRequestWithContext(ctx, method, c.baseURL+path, bytes.NewReader(requestBody))
	if err != nil {
		return zero, err
	}
	req.Header.Set("Accept", "application/json")
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}

	response, err := c.httpClient.Do(req)
	if err != nil {
		return zero, err
	}
	defer response.Body.Close()

	if response.StatusCode < 200 || response.StatusCode >= 300 {
		var apiError errorResponse
		if err := json.NewDecoder(response.Body).Decode(&apiError); err == nil && strings.TrimSpace(apiError.Error) != "" {
			return zero, errors.New(apiError.Error)
		}
		return zero, fmt.Errorf("admin api request failed: %s", response.Status)
	}

	if err := json.NewDecoder(response.Body).Decode(&zero); err != nil {
		return zero, err
	}
	return zero, nil
}
