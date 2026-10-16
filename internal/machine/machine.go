package machine

import (
	"encoding/json"
	"fmt"
	"io"

	"github.com/Mikeyphw/xbsctl/internal/version"
)

type Envelope struct {
	SchemaVersion string         `json:"schema_version"`
	OK            bool           `json:"ok"`
	App           string         `json:"app"`
	Action        string         `json:"action"`
	Message       string         `json:"message"`
	ExitCode      int            `json:"exit_code"`
	Data          map[string]any `json:"data,omitempty"`
	Error         any            `json:"error"`
}

func Emit(w io.Writer, asJSON bool, quiet bool, action string, ok bool, message string, exitCode int, data map[string]any) int {
	if asJSON {
		env := Envelope{
			SchemaVersion: version.MachineOutputVersion,
			OK:            ok,
			App:           "xbs",
			Action:        action,
			Message:       message,
			ExitCode:      exitCode,
			Data:          data,
			Error:         nil,
		}
		if !ok {
			env.Error = map[string]any{"message": message}
		}
		encoded, _ := json.MarshalIndent(env, "", "  ")
		fmt.Fprintln(w, string(encoded))
		return exitCode
	}
	if !quiet {
		if message != "" {
			fmt.Fprintln(w, message)
		} else if data != nil {
			encoded, _ := json.MarshalIndent(data, "", "  ")
			fmt.Fprintln(w, string(encoded))
		}
	}
	return exitCode
}

func OK(w io.Writer, asJSON bool, quiet bool, action string, data map[string]any) int {
	return Emit(w, asJSON, quiet, action, true, "", 0, data)
}

func Fail(w io.Writer, asJSON bool, quiet bool, action string, message string, code int, data map[string]any) int {
	return Emit(w, asJSON, quiet, action, false, message, code, data)
}
