package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"log/slog"
	"os"
)

func main() {
	logger := slog.New(slog.NewJSONHandler(os.Stderr, nil))
	logger.Info("mock stdio agent started")

	ready := map[string]string{
		"type":    "mock.ready",
		"message": "mock stdio ACP agent connected",
	}
	if envValue := os.Getenv("FERNGEIST_TEST_ENV"); envValue != "" {
		ready["env"] = envValue
	}
	if err := json.NewEncoder(os.Stdout).Encode(ready); err != nil {
		logger.Error("failed to write ready message", slog.String("error", err.Error()))
		os.Exit(1)
	}

	scanner := bufio.NewScanner(os.Stdin)
	buffer := make([]byte, 0, 64*1024)
	scanner.Buffer(buffer, 1024*1024)
	for scanner.Scan() {
		line := scanner.Text()
		if _, err := fmt.Fprintln(os.Stdout, line); err != nil {
			logger.Error("failed to write echo", slog.String("error", err.Error()))
			os.Exit(1)
		}
	}

	if err := scanner.Err(); err != nil {
		logger.Error("stdin scanner failed", slog.String("error", err.Error()))
		os.Exit(1)
	}
}
