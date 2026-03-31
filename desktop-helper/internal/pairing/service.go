package pairing

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"errors"
	"fmt"
	"log/slog"
	"sync"
	"time"

	"github.com/tamimarafat/ferngeist/desktop-helper/internal/storage"
)

const (
	defaultChallengeTTL = 2 * time.Minute
	defaultTokenTTL     = 30 * 24 * time.Hour
	codeLength          = 6
)

var (
	ErrChallengeNotFound  = errors.New("pairing challenge not found")
	ErrChallengeExpired   = errors.New("pairing challenge expired")
	ErrChallengeAmbiguous = errors.New("pairing challenge is ambiguous")
	ErrCodeMismatch       = errors.New("pairing code mismatch")
	ErrInvalidDeviceName  = errors.New("device name is required")
	ErrCredentialMissing  = errors.New("helper credential missing")
	ErrCredentialInvalid  = errors.New("helper credential invalid")
	ErrCredentialExpired  = errors.New("helper credential expired")
)

type Challenge struct {
	ID        string    `json:"id"`
	Code      string    `json:"code"`
	ExpiresAt time.Time `json:"expiresAt"`
}

type Credential struct {
	DeviceID   string    `json:"deviceId"`
	DeviceName string    `json:"deviceName"`
	Token      string    `json:"token"`
	ExpiresAt  time.Time `json:"expiresAt"`
}

// Service manages helper-local trust bootstrap. Pairing challenges are
// short-lived and in-memory; issued device credentials can be reloaded from
// SQLite so the helper survives restarts.
type Service struct {
	logger      *slog.Logger
	mu          sync.Mutex
	now         func() time.Time
	challenges  map[string]Challenge
	credentials map[string]Credential
	store       *storage.SQLiteStore
}

func NewService(logger *slog.Logger, store *storage.SQLiteStore) *Service {
	service := &Service{
		logger:      logger.With("component", "pairing"),
		now:         time.Now,
		challenges:  make(map[string]Challenge),
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

	challenge := Challenge{
		ID:        randomToken(18),
		Code:      randomCode(codeLength),
		ExpiresAt: now.Add(defaultChallengeTTL),
	}
	s.challenges[challenge.ID] = challenge
	return challenge, nil
}

// CompletePairing exchanges a valid short-lived challenge for a longer-lived
// device credential. Clients may provide either a challenge ID plus code, or a
// code alone when the helper has displayed a short code separately from the QR
// payload. Code-only completion succeeds only when that code resolves to a
// single active challenge.
func (s *Service) CompletePairing(challengeID, code, deviceName string) (Credential, error) {
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
	if code != challenge.Code {
		return Credential{}, ErrCodeMismatch
	}

	delete(s.challenges, challengeID)

	credential := Credential{
		DeviceID:   randomToken(18),
		DeviceName: deviceName,
		Token:      randomToken(32),
		ExpiresAt:  now.Add(defaultTokenTTL),
	}
	s.credentials[credential.DeviceID] = credential
	if s.store != nil {
		if err := s.store.SavePairing(context.Background(), storage.PairingRecord{
			DeviceID:   credential.DeviceID,
			DeviceName: credential.DeviceName,
			Token:      credential.Token,
			ExpiresAt:  credential.ExpiresAt,
		}); err != nil {
			s.logger.Error("persist pairing failed", "error", err)
		}
	}
	return credential, nil
}

func (s *Service) resolveChallengeLocked(challengeID, code string, now time.Time) (string, Challenge, error) {
	if challengeID != "" {
		challenge, ok := s.challenges[challengeID]
		if !ok {
			return "", Challenge{}, ErrChallengeNotFound
		}
		if now.After(challenge.ExpiresAt) {
			delete(s.challenges, challengeID)
			return "", Challenge{}, ErrChallengeExpired
		}
		return challengeID, challenge, nil
	}

	matchedID := ""
	var matched Challenge
	for id, challenge := range s.challenges {
		if now.After(challenge.ExpiresAt) {
			delete(s.challenges, id)
			continue
		}
		if challenge.Code != code {
			continue
		}
		if matchedID != "" {
			return "", Challenge{}, ErrChallengeAmbiguous
		}
		matchedID = id
		matched = challenge
	}
	if matchedID == "" {
		return "", Challenge{}, ErrChallengeNotFound
	}
	return matchedID, matched, nil
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
		if credential.Token == token {
			if now.After(credential.ExpiresAt) {
				delete(s.credentials, id)
				return Credential{}, ErrCredentialExpired
			}
			return credential, nil
		}
	}

	s.pruneExpiredCredentialsLocked(now)
	return Credential{}, ErrCredentialInvalid
}

func (s *Service) pruneExpiredLocked(now time.Time) {
	for id, challenge := range s.challenges {
		if now.After(challenge.ExpiresAt) {
			delete(s.challenges, id)
		}
	}
	s.pruneExpiredCredentialsLocked(now)
}

func (s *Service) pruneExpiredCredentialsLocked(now time.Time) {
	for id, credential := range s.credentials {
		if now.After(credential.ExpiresAt) {
			delete(s.credentials, id)
		}
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
			continue
		}
		s.credentials[record.DeviceID] = Credential{
			DeviceID:   record.DeviceID,
			DeviceName: record.DeviceName,
			Token:      record.Token,
			ExpiresAt:  record.ExpiresAt,
		}
	}
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
