//! Small fallible C interface. No Rust panic may unwind across JNA.
use df::tract::{DfParams, DfTract, RuntimeParams};
use ndarray::{ArrayView2, ArrayViewMut2};
use std::panic::{catch_unwind, AssertUnwindSafe};

#[no_mangle]
pub extern "C" fn voice_df_create(attenuation_db: f32) -> *mut DfTract {
    if !attenuation_db.is_finite() || !(0.0..=30.0).contains(&attenuation_db) {
        return std::ptr::null_mut();
    }
    catch_unwind(|| {
        let params = RuntimeParams::default_with_ch(1).with_atten_lim(attenuation_db);
        match DfTract::new(DfParams::default(), &params) {
            Ok(model) if model.sr == 48000 && model.hop_size == 480 => Box::into_raw(Box::new(model)),
            _ => std::ptr::null_mut(),
        }
    }).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub unsafe extern "C" fn voice_df_process(state: *mut DfTract, input: *const f32, output: *mut f32) -> i32 {
    if state.is_null() || input.is_null() || output.is_null() { return -1; }
    catch_unwind(AssertUnwindSafe(|| {
        let model = &mut *state;
        let input = ArrayView2::from_shape_ptr((1, 480), input);
        let output = ArrayViewMut2::from_shape_ptr((1, 480), output);
        if model.process(input, output).is_ok() { 0 } else { -1 }
    })).unwrap_or(-1)
}

#[no_mangle]
pub unsafe extern "C" fn voice_df_free(state: *mut DfTract) {
    if !state.is_null() { drop(Box::from_raw(state)); }
}
