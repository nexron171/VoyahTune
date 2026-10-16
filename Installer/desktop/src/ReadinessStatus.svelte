<script lang="ts">
  let { message, deadlineUnixMs }: { message: string; deadlineUnixMs: number } =
    $props();
  let now = $state(Date.now());
  const remaining = $derived(
    Math.max(0, Math.ceil((deadlineUnixMs - now) / 1000)),
  );
  const countdown = $derived(
    `${Math.floor(remaining / 60)}:${String(remaining % 60).padStart(2, "0")}`,
  );

  $effect(() => {
    now = Date.now();
    const timer = setInterval(() => {
      now = Date.now();
    }, 1000);
    return () => clearInterval(timer);
  });
</script>

<div class="info-note readiness-status">
  <p role="status">{message}</p>
  <p>
    {#if remaining > 0}
      До окончания проверки: <strong>{countdown}</strong>
    {:else}
      Завершаем проверку и сохраняем результат…
    {/if}
  </p>
</div>

<style>
  .info-note.readiness-status {
    display: grid;
    gap: 0.5rem;
  }
  p {
    margin: 0;
  }
  strong {
    font-variant-numeric: tabular-nums;
  }
</style>
