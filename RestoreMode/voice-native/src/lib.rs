//! Small fallible C interface. No Rust panic may unwind across JNA.
use df::tract::{DfParams, DfTract, RuntimeParams};
use ndarray::{ArrayView2, ArrayViewMut2};
use std::panic::{catch_unwind, AssertUnwindSafe};

// Clone the initialized execution state without reparsing/optimizing the models.
// Keep an untouched snapshot: DfTract::init alone does not reset recurrent state.
pub struct VoiceDf {
    initial: DfTract,
    active: DfTract,
}

#[no_mangle]
pub extern "C" fn voice_df_create(attenuation_db: f32) -> *mut VoiceDf {
    if !attenuation_db.is_finite() || !(0.0..=30.0).contains(&attenuation_db) {
        return std::ptr::null_mut();
    }
    catch_unwind(|| {
        let params = RuntimeParams::default_with_ch(1).with_atten_lim(attenuation_db);
        match DfTract::new(DfParams::default(), &params) {
            Ok(model) if model.sr == 48000 && model.hop_size == 480 =>
                Box::into_raw(Box::new(VoiceDf { active: model.clone(), initial: model })),
            _ => std::ptr::null_mut(),
        }
    }).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub unsafe extern "C" fn voice_df_process(state: *mut VoiceDf, input: *const f32, output: *mut f32) -> i32 {
    if state.is_null() || input.is_null() || output.is_null() { return -1; }
    catch_unwind(AssertUnwindSafe(|| {
        let model = &mut (*state).active;
        let input = ArrayView2::from_shape_ptr((1, 480), input);
        let output = ArrayViewMut2::from_shape_ptr((1, 480), output);
        if model.process(input, output).is_ok() { 0 } else { -1 }
    })).unwrap_or(-1)
}

#[no_mangle]
pub unsafe extern "C" fn voice_df_reset(state: *mut VoiceDf, attenuation_db: f32) -> i32 {
    if state.is_null() || !attenuation_db.is_finite() || !(0.0..=30.0).contains(&attenuation_db) {
        return -1;
    }
    catch_unwind(AssertUnwindSafe(|| {
        let state = &mut *state;
        state.active = state.initial.clone();
        state.active.set_atten_lim(attenuation_db);
        0
    })).unwrap_or(-1)
}

#[no_mangle]
pub unsafe extern "C" fn voice_df_free(state: *mut VoiceDf) {
    if !state.is_null() { drop(Box::from_raw(state)); }
}

#[cfg(test)]
mod tests {
    use super::*;

    unsafe fn audio(state: *mut VoiceDf) -> Vec<f32> {
        let mut result = Vec::new();
        for frame in 0..60 {
            let input: Vec<f32> = (0..480).map(|i| ((i + frame * 480) as f32 * 0.137).sin() * 0.1).collect();
            let mut output = vec![0.0; 480];
            assert_eq!(voice_df_process(state, input.as_ptr(), output.as_mut_ptr()), 0);
            assert!(output.iter().all(|v| v.is_finite()));
            result.extend(output);
        }
        result
    }

    #[test]
    fn reset_restores_pristine_state_without_reloading_model() {
        unsafe {
            let state = voice_df_create(6.0);
            assert!(!state.is_null());
            let first = audio(state);
            assert_eq!(voice_df_reset(state, 30.0), 0);
            audio(state);
            for _ in 0..3 {
                assert_eq!(voice_df_reset(state, 6.0), 0);
                let repeated = audio(state);
                let difference = first.iter().zip(&repeated).map(|(a,b)| (a-b).abs()).fold(0.0f32, f32::max);
                assert!(difference < 1e-6, "Previous session leaked into reset: {difference}");
            }
            assert_eq!(voice_df_reset(state, f32::NAN), -1);
            voice_df_free(state);
        }
    }
}
