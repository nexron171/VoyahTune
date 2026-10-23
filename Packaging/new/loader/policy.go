package main

import "fmt"

type attemptRecord struct {
	Boot          string          `json:"boot"`
	Identity      processIdentity `json:"identity"`
	ScriptHash    string          `json:"scriptHash"`
	State         string          `json:"state"`
	Attempts      int             `json:"attempts"`
	NextAttempt   int64           `json:"nextAttempt"`
	LastAttach    int64           `json:"lastAttach"`
	RapidRestarts int             `json:"rapidRestarts"`
	Blocked       bool            `json:"blocked"`
	Injector      processIdentity `json:"injector"`
}

func observeTarget(previous attemptRecord, boot string, identity processIdentity, hash string, now int64, crashLimit int) attemptRecord {
	if previous.Boot != boot {
		return attemptRecord{Boot: boot, Identity: identity, ScriptHash: hash}
	}
	if previous.Identity == identity {
		// An eternalized script cannot be replaced safely inside the same live process.
		if previous.ScriptHash != hash && previous.State == "active" {
			previous.State = "restart-required"
		}
		return previous
	}
	next := attemptRecord{Boot: boot, Identity: identity, ScriptHash: hash}
	if previous.ScriptHash == hash {
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

type healthObservation struct {
	Identity     processIdentity
	LastObserved int64
	LastHealthy  int64
	GraceUntil   int64
}

func (observation *healthObservation) expired(identity processIdentity, now, pulse int64) bool {
	if observation.Identity != identity {
		*observation = healthObservation{Identity: identity, GraceUntil: now + 15}
	} else if (now < observation.LastObserved || now-observation.LastObserved >= 15) && observation.GraceUntil == 0 {
		// A suspended agent may resume after the supervisor; allow its timer one bounded grace.
		observation.GraceUntil = now + 15
		observation.LastHealthy = 0
	}
	observation.LastObserved = now
	if pulse > 0 && pulse <= now && now-pulse < 15 {
		observation.LastHealthy = now
		observation.GraceUntil = 0
		return false
	}
	return now >= observation.GraceUntil && now-observation.LastHealthy >= 15
}

func identityToken(boot string, identity processIdentity) string {
	return fmt.Sprintf("%s:%d:%s", boot, identity.PID, identity.StartTicks)
}
