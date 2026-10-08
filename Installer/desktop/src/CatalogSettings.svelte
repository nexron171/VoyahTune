<script lang="ts">
  import { command, failure } from "./api";

  let {
    disabled = false,
    onsaved,
  }: { disabled?: boolean; onsaved: () => Promise<void> } = $props();
  let opened = $state(false);
  let loading = $state(false);
  let saving = $state(false);
  let loaded = $state(false);
  let catalogUrl = $state("");
  let defaultCatalogUrl = $state("");
  let errorMessage = $state("");

  function showDialog(dialog: HTMLDialogElement) {
    dialog.showModal();
    return { destroy: () => dialog.close() };
  }

  async function openSettings() {
    opened = true;
    loading = true;
    loaded = false;
    catalogUrl = "";
    defaultCatalogUrl = "";
    errorMessage = "";

    try {
      const settings = await command<{
        catalogUrl: string;
        defaultCatalogUrl: string;
      }>("catalog_settings");
      catalogUrl = settings.catalogUrl;
      defaultCatalogUrl = settings.defaultCatalogUrl;
      loaded = true;
    } catch (error) {
      errorMessage = failure(error).message;
    } finally {
      loading = false;
    }
  }

  async function saveSettings(event: SubmitEvent) {
    event.preventDefault();
    if (!loaded || saving) return;

    saving = true;
    errorMessage = "";

    try {
      await command("save_catalog_url", { url: catalogUrl.trim() });
    } catch (error) {
      errorMessage = failure(error).message;
      saving = false;
      return;
    }

    saving = false;
    opened = false;
    await onsaved();
  }
</script>

<button
  class="button secondary settings-button"
  aria-label="Настройки каталога релизов"
  title="Настройки"
  {disabled}
  onclick={openSettings}
>
  <svg
    viewBox="0 0 24 24"
    width="22"
    height="22"
    fill="none"
    stroke="currentColor"
    stroke-width="1.8"
    aria-hidden="true"
  >
    <path
      d="m9.5 3-.6 2.2-1.5.9-2.2-.6-2.5 4.3 1.6 1.6v1.8l-1.6 1.6 2.5 4.3 2.2-.6 1.5.9.6 2.2h5l.6-2.2 1.5-.9 2.2.6 2.5-4.3-1.6-1.6v-1.8l1.6-1.6-2.5-4.3-2.2.6-1.5-.9L14.5 3Z"
    />
    <circle cx="12" cy="12" r="3.2" />
  </svg>
</button>

{#if opened}
  <dialog
    use:showDialog
    class="settings-dialog"
    aria-labelledby="catalog-settings-title"
    onclose={() => (opened = false)}
    oncancel={(event) => {
      if (saving) event.preventDefault();
    }}
  >
    <form onsubmit={saveSettings} aria-busy={loading || saving}>
      <h2 id="catalog-settings-title">Настройки</h2>
      <label for="catalog-url">Адрес каталога релизов</label>
      <div class="catalog-url-field">
        <input
          id="catalog-url"
          type="url"
          bind:value={catalogUrl}
          placeholder="https://example.org/index.json"
          required
          disabled={loading || saving || !loaded}
          spellcheck="false"
          autocomplete="off"
          aria-describedby="catalog-url-hint"
        />
        <button
          class="button secondary"
          type="button"
          disabled={loading || saving || !loaded || !defaultCatalogUrl}
          onclick={() => (catalogUrl = defaultCatalogUrl)}
          >По умолчанию</button
        >
      </div>
      <p id="catalog-url-hint">
        После сохранения список релизов обновится. Скачивание и установка
        запускаются отдельно.
      </p>
      {#if loading}<p role="status">Загружаем настройки…</p>{/if}
      {#if errorMessage}<p class="settings-error" role="alert">
          {errorMessage}
        </p>{/if}
      <div class="settings-actions">
        <button
          class="button secondary"
          type="button"
          disabled={saving}
          onclick={() => (opened = false)}>Отмена</button
        >
        <button
          class="button primary"
          type="submit"
          disabled={loading || saving || !loaded || !catalogUrl.trim()}
          >{saving ? "Сохраняем…" : "Сохранить"}</button
        >
      </div>
    </form>
  </dialog>
{/if}

<style>
  .settings-button {
    min-width: 44px;
    padding: 10px;
  }

  .settings-dialog {
    margin: auto;
    max-height: 90vh;
    overflow: auto;
    background: var(--panel);
  }

  .settings-dialog::backdrop {
    background: #05090dc9;
  }

  h2 {
    margin: 0 0 24px;
  }

  label {
    display: block;
    margin-bottom: 8px;
  }

  input {
    width: 100%;
    padding: 12px;
    border: 1px solid var(--line);
    border-radius: 8px;
    background: var(--surface);
    color: var(--ink);
    min-width: 0;
  }

  .catalog-url-field {
    display: flex;
    align-items: center;
    gap: 12px;
  }

  .catalog-url-field button {
    flex-shrink: 0;
  }

  p {
    color: var(--muted);
    font-size: 13px;
    overflow-wrap: anywhere;
  }

  .settings-error {
    color: var(--red);
  }

  .settings-actions {
    display: flex;
    justify-content: flex-end;
    gap: 12px;
    margin-top: 24px;
  }
</style>
