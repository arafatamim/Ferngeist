package adminclient

import (
	"errors"
	"strings"
	"testing"
)

func TestAnnotateDaemonConnectionErrorAddsHint(t *testing.T) {
	err := annotateDaemonConnectionError(errors.New("dial tcp 127.0.0.1:5789: connect: connection refused"))
	message := err.Error()

	if !strings.Contains(message, "connection refused") {
		t.Fatalf("error message = %q, want original refusal text", message)
	}
	if !strings.Contains(message, "Is the daemon running?") {
		t.Fatalf("error message = %q, want daemon hint", message)
	}
}

func TestAnnotateDaemonConnectionErrorLeavesOtherErrorsUntouched(t *testing.T) {
	original := errors.New("context deadline exceeded")
	err := annotateDaemonConnectionError(original)

	if !errors.Is(err, original) {
		t.Fatalf("errors.Is(err, original) = false, want true")
	}
	if err.Error() != original.Error() {
		t.Fatalf("error message = %q, want %q", err.Error(), original.Error())
	}
}
