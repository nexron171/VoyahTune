package main

import (
	"context"
	"reflect"
	"testing"
)

func TestNavigationAudioAgentsNormalizeAndKeepExistingNavigationTargets(t *testing.T) {
	agents := navigationAudioAgents(" ru.yandex.yandexnavi,ru.yandex.yandexmaps,com.yango.maps.android," +
		"ru.dublgis.dgismobile,ru.yandex.yandexnavi,invalid,bad/pkg,app.pkg:service,ru.big.town.restoremode ")
	want := []string{"com.yango.maps.android", "ru.dublgis.dgismobile", "ru.yandex.yandexmaps", "ru.yandex.yandexnavi"}
	var names []string
	for _, agent := range agents {
		names = append(names, agent.Target)
		if !agent.Dynamic || !reflect.DeepEqual(agent.Modules, []string{"navigation-audio." + agent.Target}) {
			t.Fatal("audio selection did not create an independent module", agent)
		}
	}
	if !reflect.DeepEqual(names, want) {
		t.Fatal("unexpected selected audio targets", names)
	}
}

func TestNavigationAudioSharesNavigationProcessAndCanBeDisabledIndependently(t *testing.T) {
	manager, _ := supervisorFixture(t, nil)
	var existing nativeAgentConfig
	for _, agent := range compiledAgents() {
		if agent.Target == "ru.yandex.yandexnavi" {
			existing = agent
			break
		}
	}
	audio := navigationAudioAgents(existing.Target)[0]
	manager.wanted[existing.ID] = true
	manager.wanted[audio.ID] = true
	group, _, err := manager.selection(context.Background(), []nativeAgentConfig{existing, audio}, processIdentity{})
	if err != nil || !reflect.DeepEqual(group.Modules, []string{
		"client.ru.yandex.yandexnavi", "navigation-audio.ru.yandex.yandexnavi", "yandex-navigation",
	}) {
		t.Fatal("navigation modules were replaced or split", group, err)
	}
	manager.wanted[audio.ID] = false
	group, _, err = manager.selection(context.Background(), []nativeAgentConfig{existing, audio}, processIdentity{})
	if err != nil || !reflect.DeepEqual(group.Modules, []string{
		"client.ru.yandex.yandexnavi", "yandex-navigation",
	}) {
		t.Fatal("removing audio selection disabled existing navigation hooks", group, err)
	}
}

func TestNavigationAudioIncludesOnlySelectedApplicationChildProcesses(t *testing.T) {
	manager, _ := supervisorFixture(t, nil)
	workerProcess(t, manager, 42, "ru.yandex.yandexnavi:audio", "100")
	workerProcess(t, manager, 43, "ru.yandex.yandexnavi:audio", "101")
	workerProcess(t, manager, 44, "ru.yandex.yandexnavi.extra:audio", "100")
	workerProcess(t, manager, 45, "other.application:audio", "100")
	workerProcess(t, manager, 46, "ru.yandex.yandexnavi", "100")
	agents := navigationAudioProcesses(manager.procRoot, navigationAudioAgents("ru.yandex.yandexnavi"))
	if len(agents) != 2 || agents[1].Target != "ru.yandex.yandexnavi:audio" ||
		!reflect.DeepEqual(agents[1].Modules, []string{"navigation-audio.ru.yandex.yandexnavi"}) {
		t.Fatal("child process routing lost its owner or admitted another package", agents)
	}
	if agents[0].ID == agents[1].ID {
		t.Fatal("parent and child audio agents share one identity")
	}
	if agents := navigationAudioProcesses(manager.procRoot, navigationAudioAgents("")); len(agents) != 0 {
		t.Fatal("clearing selection left an audio process", agents)
	}
}
