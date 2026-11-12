package settings

import (
	"context"
	"database/sql"
	"fmt"
	"strconv"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/hwdevice"
)

// HardwarePreference keeps a config index bound to its config backend. A saved
// backend can override config without inheriting an index from another namespace.
func (s *Store) HardwarePreference(ctx context.Context, defaultHW string, defaultDevice *int) (string, *int, error) {
	rows, err := s.q.ListSettings(ctx)
	if err != nil {
		return defaultHW, nil, err
	}
	hw := defaultHW
	for _, row := range rows {
		if row.Key == KeyHWAccel {
			hw = row.Value
		}
	}
	v := "-1"
	if hw == defaultHW && hwdevice.Index(defaultDevice) != nil {
		v = strconv.Itoa(*defaultDevice)
	}
	for _, row := range rows {
		if row.Key == KeyHWDevice {
			v = row.Value
		}
	}
	n, err := strconv.Atoi(v)
	if err != nil {
		return hw, nil, fmt.Errorf("stored hardware device: %w", err)
	}
	return hw, hwdevice.Index(&n), nil
}

// SetHardwarePreference atomically stores the backend and its index. -1 records
// an explicit default rather than deleting the key and reviving a config index.
func (s *Store) SetHardwarePreference(ctx context.Context, conn *sql.DB, hw string, device *int) error {
	if err := hwdevice.Validate(hw, device); err != nil {
		return err
	}
	tx, err := conn.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback() }()
	q := s.q.WithTx(tx)
	n := "-1"
	if device = hwdevice.Index(device); device != nil {
		n = strconv.Itoa(*device)
	}
	for _, p := range []db.SetSettingParams{{Key: KeyHWAccel, Value: hw}, {Key: KeyHWDevice, Value: n}} {
		if err := q.SetSetting(ctx, p); err != nil {
			return err
		}
	}
	return tx.Commit()
}
