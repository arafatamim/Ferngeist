package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"strings"
	"sync/atomic"
	"time"
)

type rpcRequest struct {
	JSONRPC string          `json:"jsonrpc"`
	ID      json.RawMessage `json:"id"`
	Method  string          `json:"method"`
	Params  json.RawMessage `json:"params,omitempty"`
}

type rpcResponse struct {
	JSONRPC string          `json:"jsonrpc"`
	ID      json.RawMessage `json:"id"`
	Result  any             `json:"result,omitempty"`
	Error   *rpcError       `json:"error,omitempty"`
}

type rpcError struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
}

var sessionSeq atomic.Int64

var cannedResponse = "Hello! I'm Ferngeist's mock agent. This is a simulated response for review purposes."

var llmClient = &http.Client{Timeout: 120 * time.Second}

func llmEndpoint() string {
	if ep := os.Getenv("FERNGEIST_MOCK_LLM_ENDPOINT"); ep != "" {
		return ep
	}
	return "http://127.0.0.1:9234"
}

func main() {
	logger := slog.New(slog.NewJSONHandler(os.Stderr, nil))
	logger.Info("mock-stdio-agent started", slog.String("llm", llmEndpoint()))

	out := bufio.NewWriter(os.Stdout)
	defer out.Flush()

	scanner := bufio.NewScanner(os.Stdin)
	scanner.Buffer(make([]byte, 0, 64*1024), 1024*1024)

	for scanner.Scan() {
		line := scanner.Text()
		if line == "" {
			continue
		}
		var req rpcRequest
		if err := json.Unmarshal([]byte(line), &req); err != nil {
			logger.Error("parse error", slog.String("error", err.Error()))
			continue
		}
		dispatch(out, req, logger)
		if err := out.Flush(); err != nil {
			logger.Error("flush error", slog.String("error", err.Error()))
			os.Exit(1)
		}
	}
	if err := scanner.Err(); err != nil {
		logger.Error("stdin error", slog.String("error", err.Error()))
		os.Exit(1)
	}
}

func dispatch(w *bufio.Writer, req rpcRequest, logger *slog.Logger) {
	switch req.Method {
	case "initialize":
		agentInfo := map[string]any{
			"name":    "mock-acp",
			"title":   "Mock ACP Agent",
			"version": "1.0.0",
		}
		if envValue := os.Getenv("FERNGEIST_TEST_ENV"); envValue != "" {
			agentInfo["env"] = envValue
		}
		writeResult(w, req, map[string]any{
			"protocolVersion": 1,
			"agentCapabilities": map[string]any{
				"loadSession": true,
				"promptCapabilities": map[string]any{
					"image":           false,
					"audio":           false,
					"embeddedContext": false,
				},
				"mcpCapabilities": map[string]any{
					"http": false,
					"sse":  false,
				},
				"sessionCapabilities": map[string]any{
					"list":   map[string]any{},
					"resume": map[string]any{},
					"fork":   map[string]any{},
				},
			},
			"agentInfo":   agentInfo,
			"authMethods": []any{},
		})
	case "authenticate":
		writeResult(w, req, map[string]any{})
	case "session/new":
		id := fmt.Sprintf("mock_sess_%d", sessionSeq.Add(1))
		writeResult(w, req, map[string]any{"sessionId": id})
		writeNotif(w, "session/update", map[string]any{
			"sessionId": id,
			"update": map[string]any{
				"sessionUpdate": "session_info_update",
				"title":         "Ferngeist Review",
			},
		})
	case "session/prompt":
		handlePrompt(w, req, logger)
	case "session/cancel":
		writeResult(w, req, nil)
	case "session/list":
		writeResult(w, req, map[string]any{
			"sessions": []any{
				map[string]any{
					"sessionId": "mock_sess_1",
					"cwd":       "/home/user/project",
					"title":      "Mock session for review",
					"updatedAt":  "2025-12-01T12:00:00Z",
				},
			},
		})
	case "session/load":
		writeNotif(w, "session/update", map[string]any{
			"sessionId": "mock_sess_1",
			"update": map[string]any{
				"sessionUpdate": "user_message_chunk",
				"content": map[string]any{
					"type": "text",
					"text": "What is this?",
				},
			},
		})
		writeNotif(w, "session/update", map[string]any{
			"sessionId": "mock_sess_1",
			"update": map[string]any{
				"sessionUpdate": "agent_message_chunk",
				"content": map[string]any{
					"type": "text",
					"text": "This is Ferngeist, an Android app for connecting to ACP agents.",
				},
			},
		})
		writeResult(w, req, nil)
	case "session/set_mode":
		writeResult(w, req, nil)
	case "session/set_model":
		writeResult(w, req, nil)
	case "session/set_config_option":
		writeResult(w, req, map[string]any{"configOptions": []any{}})
	default:
		logger.Warn("unknown method", slog.String("method", req.Method))
		writeError(w, req, -32601, fmt.Sprintf("Method not found: %s", req.Method))
	}
}

func handlePrompt(w *bufio.Writer, req rpcRequest, logger *slog.Logger) {
	var params struct {
		SessionID string `json:"sessionId"`
		Prompt    []struct {
			Type string `json:"type"`
			Text string `json:"text"`
		} `json:"prompt"`
	}
	if len(req.Params) > 0 {
		_ = json.Unmarshal(req.Params, &params)
	}

	reply := callLLM(params.Prompt, logger)
	if reply == "" {
		reply = cannedResponse
	}

	if params.SessionID != "" {
		writeNotif(w, "session/update", map[string]any{
			"sessionId": params.SessionID,
			"update": map[string]any{
				"sessionUpdate": "agent_message_chunk",
				"content": map[string]any{
					"type": "text",
					"text": reply,
				},
			},
		})
	}

	writeResult(w, req, map[string]any{"stopReason": "end_turn"})
}

func callLLM(prompt []struct {
	Type string `json:"type"`
	Text string `json:"text"`
}, logger *slog.Logger) string {
	messages := []map[string]string{
		{"role": "system", "content": "You are Ferngeist, an Android app that connects users to AI agents via the Agent Client Protocol (ACP). You help users manage coding sessions, connect to desktop agents, scan QR codes to pair, and browse session history. Keep answers short and helpful. You cannot edit files or run commands — you refer users to their connected agent for that."},
	}
	for _, block := range prompt {
		if block.Type == "text" && strings.TrimSpace(block.Text) != "" {
			messages = append(messages, map[string]string{"role": "user", "content": block.Text})
		}
	}

	body, _ := json.Marshal(map[string]any{
		"model":       "gemma-3-270m-it",
		"messages":    messages,
		"max_tokens":  512,
		"temperature": 1.0,
		"top_k":       64,
		"top_p":       0.95,
		"min_p":       0.0,
		"stream":      false,
	})

	endpoint := llmEndpoint()
	resp, err := llmClient.Post(endpoint+"/v1/chat/completions", "application/json", strings.NewReader(string(body)))
	if err != nil {
		logger.Warn("llm request failed", slog.String("error", err.Error()))
		return ""
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		logger.Warn("llm non-200 status", slog.Int("status", resp.StatusCode))
		return ""
	}

	respBody, err := io.ReadAll(resp.Body)
	if err != nil {
		logger.Warn("llm read body failed", slog.String("error", err.Error()))
		return ""
	}

	var completion struct {
		Choices []struct {
			Message struct {
				Content string `json:"content"`
			} `json:"message"`
		} `json:"choices"`
	}
	if err := json.Unmarshal(respBody, &completion); err != nil {
		logger.Warn("llm parse failed", slog.String("error", err.Error()))
		return ""
	}
	if len(completion.Choices) == 0 {
		logger.Warn("llm empty choices")
		return ""
	}
	return strings.TrimSpace(completion.Choices[0].Message.Content)
}

func writeResult(w *bufio.Writer, req rpcRequest, result any) {
	resp := rpcResponse{JSONRPC: "2.0", ID: req.ID, Result: result}
	if result == nil {
		var null json.RawMessage = []byte("null")
		resp.Result = null
	}
	data, _ := json.Marshal(resp)
	fmt.Fprintln(w, string(data))
}

func writeError(w *bufio.Writer, req rpcRequest, code int, msg string) {
	data, _ := json.Marshal(rpcResponse{
		JSONRPC: "2.0",
		ID:      req.ID,
		Error:   &rpcError{Code: code, Message: msg},
	})
	fmt.Fprintln(w, string(data))
}

func writeNotif(w *bufio.Writer, method string, params any) {
	data, _ := json.Marshal(map[string]any{
		"jsonrpc": "2.0",
		"method":  method,
		"params":  params,
	})
	fmt.Fprintln(w, string(data))
}