#![no_std]

extern crate alloc;

extern crate contract_ffi;

use alloc::string::{String, ToString};
use alloc::vec::Vec;

use contract_ffi::contract_api::TURef;
use contract_ffi::contract_api::{runtime, storage, Error as ApiError};
use contract_ffi::key::Key;
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;
use contract_ffi::uref::{AccessRights, URef};

const CONTRACT_POINTER: u32 = 0;

#[repr(u16)]
enum Error {
    GetArgument = 0,
}

const REPLACEMENT_DATA: &str = "bawitdaba";

#[no_mangle]
pub extern "C" fn call() {
    let arg: Key = runtime::get_arg(CONTRACT_POINTER)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);

    let contract_pointer = arg
        .to_c_ptr()
        .unwrap_or_revert_with(ApiError::User(Error::GetArgument as u16));

    let reference: URef = runtime::call_contract(contract_pointer, &(), &Vec::new());

    let forged_reference: TURef<String> = {
        let ret = URef::new(reference.addr(), AccessRights::READ_ADD_WRITE);
        TURef::from_uref(ret).unwrap_or_revert()
    };

    storage::write(forged_reference, REPLACEMENT_DATA.to_string())
}
