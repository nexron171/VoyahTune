package main

import "testing"

func TestNavigationAppMayStartLongAfterBoot(t *testing.T) {
	budget := injectionBudget{}
	for poll := 0; poll < 1000; poll++ {
		if budget.observe(0) {
			t.Fatal("no application process must not trigger injection")
		}
	}
	if !budget.observe(123) || budget.remaining != maxRepl {
		t.Fatal("waiting for a navigator consumed its retry budget")
	}
}

func TestNavigationRetriesAreBoundedPerProcess(t *testing.T) {
	budget := injectionBudget{}
	for attempt := 0; attempt < maxRepl; attempt++ {
		if !budget.observe(123) {
			t.Fatal("retry budget exhausted early")
		}
		budget.remaining--
	}
	if budget.observe(123) {
		t.Fatal("failed injection must eventually stop retrying the same process")
	}
	if !budget.observe(456) || budget.remaining != maxRepl {
		t.Fatal("a restarted navigator needs a new retry budget")
	}
	budget.remaining = 0
	budget.observe(0)
	if !budget.observe(456) {
		t.Fatal("a reused PID after process exit needs a new retry budget")
	}
}
