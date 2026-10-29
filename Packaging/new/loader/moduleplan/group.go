package moduleplan

import (
	"errors"
	"sort"
)

type Selection struct {
	ID      string
	Target  string
	Modules []string
	Enabled bool
}

type TargetGroup struct {
	Target  string
	Modules []string
	Members []Selection
}

func Group(selections []Selection) ([]TargetGroup, error) {
	owners := map[string]bool{}
	targets := map[string]*TargetGroup{}
	modules := map[string]map[string]bool{}
	for _, selection := range selections {
		if selection.ID == "" || selection.Target == "" || len(selection.Modules) == 0 || owners[selection.ID] {
			return nil, errors.New("unique agent owner, target and module IDs required")
		}
		owners[selection.ID] = true
		target := targets[selection.Target]
		if target == nil {
			target = &TargetGroup{Target: selection.Target, Modules: []string{}}
			targets[selection.Target] = target
			modules[selection.Target] = map[string]bool{}
		}
		selection.Modules = append([]string{}, selection.Modules...)
		target.Members = append(target.Members, selection)
		for _, module := range selection.Modules {
			if module == "" {
				return nil, errors.New("empty module ID")
			}
			if selection.Enabled {
				modules[selection.Target][module] = true
			}
		}
	}
	result := make([]TargetGroup, 0, len(targets))
	for name, target := range targets {
		selected := modules[name]
		if selected["keyboard-en"] && selected["keyboard-ru"] {
			return nil, errors.New("one keyboard module per process required")
		}
		for module := range selected {
			target.Modules = append(target.Modules, module)
		}
		sort.Strings(target.Modules)
		sort.Slice(target.Members, func(left, right int) bool { return target.Members[left].ID < target.Members[right].ID })
		result = append(result, *target)
	}
	sort.Slice(result, func(left, right int) bool { return result[left].Target < result[right].Target })
	return result, nil
}
