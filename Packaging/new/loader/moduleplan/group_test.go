package moduleplan

import (
	"reflect"
	"testing"
)

func TestGroupsMergeEnabledModulesForOneTarget(t *testing.T) {
	input := []Selection{
		{ID: "drive-reset", Target: "vehicle", Modules: []string{"drive-reset"}, Enabled: true},
		{ID: "apollo", Target: "vehicle", Modules: []string{"apollo"}, Enabled: true},
		{ID: "rds", Target: "radio", Modules: []string{"rds", "client.radio"}, Enabled: true},
		{ID: "keyboard-en", Target: "keyboard", Modules: []string{"keyboard-en"}},
		{ID: "keyboard-ru", Target: "keyboard", Modules: []string{"keyboard-ru"}, Enabled: true},
	}
	groups, err := Group(input)
	if err != nil {
		t.Fatal(err)
	}
	if len(groups) != 3 || !reflect.DeepEqual(groups[0].Modules, []string{"keyboard-ru"}) || !reflect.DeepEqual(groups[1].Modules, []string{"client.radio", "rds"}) || !reflect.DeepEqual(groups[2].Modules, []string{"apollo", "drive-reset"}) {
		t.Fatal(groups)
	}
	input[0].Modules[0] = "changed"
	if groups[2].Modules[1] != "drive-reset" {
		t.Fatal("caller mutation changed a prepared plan")
	}
}
func TestGroupsKeepEmptyTargetForResidentRemoval(t *testing.T) {
	groups, err := Group([]Selection{{ID: "client.app", Target: "app", Modules: []string{"client.app"}}})
	if err != nil || len(groups) != 1 || len(groups[0].Modules) != 0 {
		t.Fatal(groups, err)
	}
}
func TestGroupsRejectConflictingOwnerAndKeyboardChoices(t *testing.T) {
	for _, input := range [][]Selection{
		{{ID: "same", Target: "first", Modules: []string{"phone"}, Enabled: true}, {ID: "same", Target: "second", Modules: []string{"phone"}, Enabled: true}},
		{{ID: "keyboard-en", Target: "keyboard", Modules: []string{"keyboard-en"}, Enabled: true}, {ID: "keyboard-ru", Target: "keyboard", Modules: []string{"keyboard-ru"}, Enabled: true}},
		{{ID: "invalid", Target: "app", Enabled: true}},
	} {
		if groups, err := Group(input); err == nil {
			t.Fatal("invalid plan accepted", groups)
		}
	}
}
