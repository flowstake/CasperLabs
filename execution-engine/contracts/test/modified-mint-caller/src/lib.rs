#![no_std]

#[macro_use]
extern crate alloc;
extern crate contract_ffi;

use alloc::string::String;

use contract_ffi::contract_api::{runtime, storage, system};
use contract_ffi::key::Key;

const NEW_ENDPOINT_NAME: &str = "version";
const RESULT_TUREF_NAME: &str = "output_version";

#[no_mangle]
pub extern "C" fn call() {
    let mint_pointer = system::get_mint();
    let value: String = runtime::call_contract(mint_pointer, &(NEW_ENDPOINT_NAME,), &vec![]);
    let value_turef = storage::new_turef(value);
    let key = Key::URef(value_turef.into());
    runtime::put_key(RESULT_TUREF_NAME, &key);
}
