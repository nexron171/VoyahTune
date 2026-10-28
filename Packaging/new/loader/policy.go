package main

type attemptRecord struct {
	Boot          string          `json:"boot"`
	Identity      processIdentity `json:"identity"`
	CodeID        string          `json:"codeId"`
	State         string          `json:"state"`
	LastError     string          `json:"lastError,omitempty"`
	Attempts      int             `json:"attempts"`
	NextAttempt   int64           `json:"nextAttempt"`
	LastAttach    int64           `json:"lastAttach"`
	RapidRestarts int             `json:"rapidRestarts"`
	Blocked       bool            `json:"blocked"`
}

func (record attemptRecord) failureReason() string {
	if record.LastError != "" {
		return record.LastError
	}
	return "attempt budget consumed or backoff pending"
}

func observeTarget(previous attemptRecord, boot string, identity processIdentity, codeID string, now int64, crashLimit int) attemptRecord {
	if previous.Boot != boot {
		return attemptRecord{Boot: boot, Identity: identity, CodeID: codeID}
	}
	if previous.Identity == identity {
		// Resident code cannot be replaced safely inside a live process.
		if previous.CodeID != codeID && previous.State == "active" {
			previous.State = "restart-required"
		}
		return previous
	}
	next := attemptRecord{Boot: boot, Identity: identity, CodeID: codeID}
	if previous.CodeID == codeID {
		next.Blocked = previous.Blocked
		if previous.Identity.PID > 0 && now >= previous.LastAttach && now-previous.LastAttach < 60 {
			next.RapidRestarts = previous.RapidRestarts + 1
		}
	}
	if crashLimit > 0 && next.RapidRestarts >= crashLimit {
		next.Blocked = true
	}
	return next
}

func (record attemptRecord) canAttempt(now int64, maximum int) bool {
	return !record.Blocked && record.Identity.PID > 0 && record.State != "active" && record.State != "restart-required" && record.Attempts < maximum && now >= record.NextAttempt
}

func retryDelay(attempt int) int64 {
	switch attempt {
	case 1:
		return 1
	case 2:
		return 2
	case 3:
		return 5
	default:
		return 30
	}
}
