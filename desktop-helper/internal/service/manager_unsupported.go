//go:build !linux && !windows

package service

import (
	"fmt"
	goruntime "runtime"
)

func newOSManager() Manager {
	return unsupportedManager{
		err: fmt.Errorf("%w: %s", ErrServiceUnsupportedOS, goruntime.GOOS),
	}
}
