/* Mock-only transport. This file never invokes ADB, shell commands or network APIs. */
(function (root) {
  'use strict';
  const model = root.VoyahModel;
  class DemoAdapter {
    constructor() {
      this.connection = 'none'; this.installed = 'none'; this.fault = 'none';
      this.failureConsumed = false; this.timer = null; this.running = false;
    }
    async scan() {
      await new Promise(resolve => setTimeout(resolve, 550));
      return structuredClone(model.connectionStates[this.connection]);
    }
    async run({ action, from = 0, emit }) {
      this.stop();
      this.running = true;
      const steps = action === 'remove' ? model.removeSteps : model.installSteps;
      let step = from, progress = 0;
      const tick = () => {
        if (!this.running) return;
        if (progress === 0) emit({ type: 'step-started', step, label: steps[step] });
        progress += 20;
        emit({ type: 'progress', step, progress });
        if (progress >= 60 && !this.failureConsumed && model.faultStep(this.fault, action) === step) {
          this.failureConsumed = true; this.running = false;
          emit({ type: 'failed', step, error: model.errorInfo(this.fault, action) });
          return;
        }
        if (progress >= 100) {
          emit({ type: 'step-succeeded', step, label: steps[step] });
          step++; progress = 0;
          if (step === steps.length) {
            this.running = false; this.installed = action === 'remove' ? 'none' : action;
            emit({ type: 'completed' }); return;
          }
        }
        this.timer = setTimeout(tick, 260);
      };
      this.timer = setTimeout(tick, 300);
    }
    stop() { clearTimeout(this.timer); this.running = false; }
  }
  root.VoyahDemoAdapter = DemoAdapter;
})(window);
