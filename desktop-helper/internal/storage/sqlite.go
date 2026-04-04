package storage

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"time"

	_ "modernc.org/sqlite"
)

var ErrNotFound = errors.New("record not found")

type PairingRecord struct {
	DeviceID       string
	DeviceName     string
	Token          string
	ExpiresAt      time.Time
	Scopes         []string
	ProofPublicKey string
}

type RuntimeRecord struct {
	RuntimeID string
	AgentID   string
	AgentName string
	Status    string
	Command   string
	PID       int
	LastError string
	CreatedAt time.Time
}

type RuntimeTokenRecord struct {
	RuntimeID string
	Token     string
	ExpiresAt time.Time
}

type RuntimeFailureRecord struct {
	RuntimeID  string
	AgentID    string
	AgentName  string
	LastError  string
	CreatedAt  time.Time
	FailedAt   time.Time
	LogPreview string
}

type HelperSettingsRecord struct {
	RegistryURL   string
	PublicBaseURL string
	EnableLAN     bool
	HelperName    string
}

type AcquiredBinaryRecord struct {
	AgentID     string
	Version     string
	Path        string
	ArchiveURL  string
	InstalledAt time.Time
}

// SQLiteStore is the helper's only durable state store. It is intentionally
// limited to pairings, helper settings, runtime metadata, runtime tokens, and
// recent failures.
type SQLiteStore struct {
	db *sql.DB
}

// Open initializes the SQLite database and applies idempotent schema
// migrations before the helper starts serving requests.
func Open(path string) (*SQLiteStore, error) {
	db, err := sql.Open("sqlite", path)
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	db.SetMaxIdleConns(1)

	store := &SQLiteStore{db: db}
	if err := store.migrate(context.Background()); err != nil {
		_ = db.Close()
		return nil, err
	}
	return store, nil
}

func (s *SQLiteStore) Close() error {
	if s == nil || s.db == nil {
		return nil
	}
	return s.db.Close()
}

func (s *SQLiteStore) SavePairing(ctx context.Context, record PairingRecord) error {
	scopesJSON, err := json.Marshal(record.Scopes)
	if err != nil {
		return err
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err := tx.ExecContext(
		ctx,
		`INSERT INTO paired_devices(device_id, device_name, token, expires_at)
		 VALUES (?, ?, ?, ?)
		 ON CONFLICT(device_id) DO UPDATE SET
		   device_name = excluded.device_name,
		   token = excluded.token,
		   expires_at = excluded.expires_at`,
		record.DeviceID,
		record.DeviceName,
		record.Token,
		record.ExpiresAt.UTC().Format(time.RFC3339Nano),
	); err != nil {
		return err
	}
	if _, err := tx.ExecContext(
		ctx,
		`INSERT INTO paired_device_scopes(device_id, scopes)
		 VALUES (?, ?)
		 ON CONFLICT(device_id) DO UPDATE SET scopes = excluded.scopes`,
		record.DeviceID,
		string(scopesJSON),
	); err != nil {
		return err
	}
	if _, err := tx.ExecContext(
		ctx,
		`INSERT INTO paired_device_proofs(device_id, public_key)
		 VALUES (?, ?)
		 ON CONFLICT(device_id) DO UPDATE SET public_key = excluded.public_key`,
		record.DeviceID,
		record.ProofPublicKey,
	); err != nil {
		return err
	}
	return tx.Commit()
}

func (s *SQLiteStore) ListPairings(ctx context.Context) ([]PairingRecord, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT p.device_id, p.device_name, p.token, p.expires_at, COALESCE(s.scopes, ''), COALESCE(k.public_key, '') FROM paired_devices p LEFT JOIN paired_device_scopes s ON s.device_id = p.device_id LEFT JOIN paired_device_proofs k ON k.device_id = p.device_id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var records []PairingRecord
	for rows.Next() {
		var (
			record      PairingRecord
			expiresRaw  string
			scopesRaw   string
			proofKeyRaw string
		)
		if err := rows.Scan(&record.DeviceID, &record.DeviceName, &record.Token, &expiresRaw, &scopesRaw, &proofKeyRaw); err != nil {
			return nil, err
		}
		record.ExpiresAt, err = time.Parse(time.RFC3339Nano, expiresRaw)
		if err != nil {
			return nil, err
		}
		if scopesRaw != "" {
			if err := json.Unmarshal([]byte(scopesRaw), &record.Scopes); err != nil {
				return nil, err
			}
		}
		record.ProofPublicKey = proofKeyRaw
		records = append(records, record)
	}
	return records, rows.Err()
}

func (s *SQLiteStore) DeletePairing(ctx context.Context, deviceID string) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	_, err = tx.ExecContext(ctx, `DELETE FROM paired_device_scopes WHERE device_id = ?`, deviceID)
	if err != nil {
		return err
	}
	_, err = tx.ExecContext(ctx, `DELETE FROM paired_device_proofs WHERE device_id = ?`, deviceID)
	if err != nil {
		return err
	}
	result, err := tx.ExecContext(ctx, `DELETE FROM paired_devices WHERE device_id = ?`, deviceID)
	if err != nil {
		return err
	}
	rowsAffected, err := result.RowsAffected()
	if err != nil {
		return err
	}
	if rowsAffected == 0 {
		return ErrNotFound
	}
	return tx.Commit()
}

// SaveRuntime persists the latest runtime snapshot rather than a full event
// history. This keeps the store small and restart recovery simple.
func (s *SQLiteStore) SaveRuntime(ctx context.Context, record RuntimeRecord) error {
	_, err := s.db.ExecContext(
		ctx,
		`INSERT INTO runtimes(runtime_id, agent_id, agent_name, status, command, pid, last_error, created_at)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?)
		 ON CONFLICT(runtime_id) DO UPDATE SET
		   agent_id = excluded.agent_id,
		   agent_name = excluded.agent_name,
		   status = excluded.status,
		   command = excluded.command,
		   pid = excluded.pid,
		   last_error = excluded.last_error,
		   created_at = excluded.created_at`,
		record.RuntimeID,
		record.AgentID,
		record.AgentName,
		record.Status,
		record.Command,
		record.PID,
		record.LastError,
		record.CreatedAt.UTC().Format(time.RFC3339Nano),
	)
	return err
}

func (s *SQLiteStore) UpdateRuntimeStatus(ctx context.Context, runtimeID, status, lastError string, pid int) error {
	result, err := s.db.ExecContext(
		ctx,
		`UPDATE runtimes SET status = ?, last_error = ?, pid = ? WHERE runtime_id = ?`,
		status,
		lastError,
		pid,
		runtimeID,
	)
	if err != nil {
		return err
	}
	rowsAffected, err := result.RowsAffected()
	if err != nil {
		return err
	}
	if rowsAffected == 0 {
		return ErrNotFound
	}
	return nil
}

func (s *SQLiteStore) ListRuntimes(ctx context.Context) ([]RuntimeRecord, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT runtime_id, agent_id, agent_name, status, command, pid, last_error, created_at FROM runtimes`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var records []RuntimeRecord
	for rows.Next() {
		var (
			record     RuntimeRecord
			createdRaw string
		)
		if err := rows.Scan(
			&record.RuntimeID,
			&record.AgentID,
			&record.AgentName,
			&record.Status,
			&record.Command,
			&record.PID,
			&record.LastError,
			&createdRaw,
		); err != nil {
			return nil, err
		}
		record.CreatedAt, err = time.Parse(time.RFC3339Nano, createdRaw)
		if err != nil {
			return nil, err
		}
		records = append(records, record)
	}
	return records, rows.Err()
}

func (s *SQLiteStore) SaveRuntimeToken(ctx context.Context, record RuntimeTokenRecord) error {
	_, err := s.db.ExecContext(
		ctx,
		`INSERT INTO runtime_tokens(runtime_id, token, expires_at)
		 VALUES (?, ?, ?)
		 ON CONFLICT(runtime_id) DO UPDATE SET
		   token = excluded.token,
		   expires_at = excluded.expires_at`,
		record.RuntimeID,
		record.Token,
		record.ExpiresAt.UTC().Format(time.RFC3339Nano),
	)
	return err
}

func (s *SQLiteStore) GetRuntimeToken(ctx context.Context, runtimeID string) (RuntimeTokenRecord, error) {
	row := s.db.QueryRowContext(ctx, `SELECT runtime_id, token, expires_at FROM runtime_tokens WHERE runtime_id = ?`, runtimeID)

	var (
		record     RuntimeTokenRecord
		expiresRaw string
	)
	if err := row.Scan(&record.RuntimeID, &record.Token, &expiresRaw); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return RuntimeTokenRecord{}, ErrNotFound
		}
		return RuntimeTokenRecord{}, err
	}

	expiresAt, err := time.Parse(time.RFC3339Nano, expiresRaw)
	if err != nil {
		return RuntimeTokenRecord{}, err
	}
	record.ExpiresAt = expiresAt
	return record, nil
}

func (s *SQLiteStore) DeleteRuntimeToken(ctx context.Context, runtimeID string) error {
	_, err := s.db.ExecContext(ctx, `DELETE FROM runtime_tokens WHERE runtime_id = ?`, runtimeID)
	return err
}

func (s *SQLiteStore) DeleteExpiredRuntimeTokens(ctx context.Context, now time.Time) error {
	_, err := s.db.ExecContext(ctx, `DELETE FROM runtime_tokens WHERE expires_at <= ?`, now.UTC().Format(time.RFC3339Nano))
	return err
}

func (s *SQLiteStore) DeleteAllRuntimeTokens(ctx context.Context) error {
	_, err := s.db.ExecContext(ctx, `DELETE FROM runtime_tokens`)
	return err
}

func (s *SQLiteStore) SaveRuntimeFailure(ctx context.Context, record RuntimeFailureRecord) error {
	_, err := s.db.ExecContext(
		ctx,
		`INSERT INTO runtime_failures(runtime_id, agent_id, agent_name, last_error, created_at, failed_at, log_preview)
		 VALUES (?, ?, ?, ?, ?, ?, ?)`,
		record.RuntimeID,
		record.AgentID,
		record.AgentName,
		record.LastError,
		record.CreatedAt.UTC().Format(time.RFC3339Nano),
		record.FailedAt.UTC().Format(time.RFC3339Nano),
		record.LogPreview,
	)
	return err
}

func (s *SQLiteStore) SaveHelperSettings(ctx context.Context, record HelperSettingsRecord) error {
	_, err := s.db.ExecContext(
		ctx,
		`INSERT INTO helper_settings(settings_key, registry_url, public_base_url, enable_lan, helper_name)
		 VALUES ('default', ?, ?, ?, ?)
		 ON CONFLICT(settings_key) DO UPDATE SET
		   registry_url = excluded.registry_url,
		   public_base_url = excluded.public_base_url,
		   enable_lan = excluded.enable_lan,
		   helper_name = excluded.helper_name`,
		record.RegistryURL,
		record.PublicBaseURL,
		boolToSQLiteInt(record.EnableLAN),
		record.HelperName,
	)
	return err
}

func (s *SQLiteStore) SaveAcquiredBinary(ctx context.Context, record AcquiredBinaryRecord) error {
	_, err := s.db.ExecContext(
		ctx,
		`INSERT INTO acquired_binaries(agent_id, version, path, archive_url, installed_at)
		 VALUES (?, ?, ?, ?, ?)
		 ON CONFLICT(agent_id) DO UPDATE SET
		   version = excluded.version,
		   path = excluded.path,
		   archive_url = excluded.archive_url,
		   installed_at = excluded.installed_at`,
		record.AgentID,
		record.Version,
		record.Path,
		record.ArchiveURL,
		record.InstalledAt.UTC().Format(time.RFC3339Nano),
	)
	return err
}

func (s *SQLiteStore) GetAcquiredBinary(ctx context.Context, agentID string) (AcquiredBinaryRecord, error) {
	row := s.db.QueryRowContext(
		ctx,
		`SELECT agent_id, version, path, archive_url, installed_at
		 FROM acquired_binaries
		 WHERE agent_id = ?`,
		agentID,
	)

	var (
		record       AcquiredBinaryRecord
		installedRaw string
	)
	if err := row.Scan(&record.AgentID, &record.Version, &record.Path, &record.ArchiveURL, &installedRaw); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return AcquiredBinaryRecord{}, ErrNotFound
		}
		return AcquiredBinaryRecord{}, err
	}
	installedAt, err := time.Parse(time.RFC3339Nano, installedRaw)
	if err != nil {
		return AcquiredBinaryRecord{}, err
	}
	record.InstalledAt = installedAt
	return record, nil
}

func (s *SQLiteStore) GetHelperSettings(ctx context.Context) (HelperSettingsRecord, error) {
	row := s.db.QueryRowContext(
		ctx,
		`SELECT registry_url, public_base_url, enable_lan, helper_name
		 FROM helper_settings
		 WHERE settings_key = 'default'`,
	)

	var (
		record    HelperSettingsRecord
		enableLAN int
	)
	if err := row.Scan(&record.RegistryURL, &record.PublicBaseURL, &enableLAN, &record.HelperName); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return HelperSettingsRecord{}, ErrNotFound
		}
		return HelperSettingsRecord{}, err
	}
	record.EnableLAN = enableLAN != 0
	return record, nil
}

// ListRecentRuntimeFailures returns persisted failure summaries for diagnostics.
// It deliberately stores a bounded log preview instead of full runtime logs.
func (s *SQLiteStore) ListRecentRuntimeFailures(ctx context.Context, limit int) ([]RuntimeFailureRecord, error) {
	if limit <= 0 {
		limit = 5
	}

	rows, err := s.db.QueryContext(
		ctx,
		`SELECT runtime_id, agent_id, agent_name, last_error, created_at, failed_at, log_preview
		 FROM runtime_failures
		 ORDER BY failed_at DESC
		 LIMIT ?`,
		limit,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var records []RuntimeFailureRecord
	for rows.Next() {
		var (
			record     RuntimeFailureRecord
			createdRaw string
			failedRaw  string
		)
		if err := rows.Scan(
			&record.RuntimeID,
			&record.AgentID,
			&record.AgentName,
			&record.LastError,
			&createdRaw,
			&failedRaw,
			&record.LogPreview,
		); err != nil {
			return nil, err
		}
		record.CreatedAt, err = time.Parse(time.RFC3339Nano, createdRaw)
		if err != nil {
			return nil, err
		}
		record.FailedAt, err = time.Parse(time.RFC3339Nano, failedRaw)
		if err != nil {
			return nil, err
		}
		records = append(records, record)
	}
	return records, rows.Err()
}

// migrate is intentionally append-only and idempotent because the helper is a
// local daemon, not a service with a heavyweight migration framework.
func (s *SQLiteStore) migrate(ctx context.Context) error {
	statements := []string{
		`CREATE TABLE IF NOT EXISTS paired_devices (
			device_id TEXT PRIMARY KEY,
			device_name TEXT NOT NULL,
			token TEXT NOT NULL,
			expires_at TEXT NOT NULL
		)`,
		`CREATE TABLE IF NOT EXISTS paired_device_scopes (
			device_id TEXT PRIMARY KEY,
			scopes TEXT NOT NULL DEFAULT '[]'
		)`,
		`CREATE TABLE IF NOT EXISTS paired_device_proofs (
			device_id TEXT PRIMARY KEY,
			public_key TEXT NOT NULL DEFAULT ''
		)`,
		`CREATE TABLE IF NOT EXISTS runtimes (
			runtime_id TEXT PRIMARY KEY,
			agent_id TEXT NOT NULL,
			agent_name TEXT NOT NULL,
			status TEXT NOT NULL,
			command TEXT NOT NULL,
			pid INTEGER NOT NULL,
			last_error TEXT NOT NULL DEFAULT '',
			created_at TEXT NOT NULL
		)`,
		`CREATE TABLE IF NOT EXISTS runtime_tokens (
			runtime_id TEXT PRIMARY KEY,
			token TEXT NOT NULL,
			expires_at TEXT NOT NULL
		)`,
		`CREATE TABLE IF NOT EXISTS runtime_failures (
			id INTEGER PRIMARY KEY AUTOINCREMENT,
			runtime_id TEXT NOT NULL,
			agent_id TEXT NOT NULL,
			agent_name TEXT NOT NULL,
			last_error TEXT NOT NULL,
			created_at TEXT NOT NULL,
			failed_at TEXT NOT NULL,
			log_preview TEXT NOT NULL DEFAULT ''
		)`,
		`CREATE TABLE IF NOT EXISTS helper_settings (
			settings_key TEXT PRIMARY KEY,
			registry_url TEXT NOT NULL DEFAULT '',
			public_base_url TEXT NOT NULL DEFAULT '',
			enable_lan INTEGER NOT NULL DEFAULT 0,
			helper_name TEXT NOT NULL DEFAULT ''
		)`,
		`CREATE TABLE IF NOT EXISTS acquired_binaries (
			agent_id TEXT PRIMARY KEY,
			version TEXT NOT NULL DEFAULT '',
			path TEXT NOT NULL,
			archive_url TEXT NOT NULL DEFAULT '',
			installed_at TEXT NOT NULL
		)`,
	}

	for _, statement := range statements {
		if _, err := s.db.ExecContext(ctx, statement); err != nil {
			return err
		}
	}
	return nil
}

func boolToSQLiteInt(value bool) int {
	if value {
		return 1
	}
	return 0
}
