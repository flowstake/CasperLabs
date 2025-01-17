#![no_std]

#[macro_use]
extern crate alloc;
extern crate contract_ffi;

use contract_ffi::contract_api::{runtime, Error};
use contract_ffi::contract_api::{ContractRef, TURef};
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;
use contract_ffi::uref::URef;

#[repr(u32)]
enum Args {
    LocalStateURef = 0,
}

#[repr(u16)]
enum CustomError {
    MissingLocalStateURefArg = 11,
}

#[no_mangle]
pub extern "C" fn call() {
    let local_state_uref: URef = runtime::get_arg(Args::LocalStateURef as u32)
        .unwrap_or_revert_with(Error::User(CustomError::MissingLocalStateURefArg as u16))
        .unwrap_or_revert_with(Error::InvalidArgument);

    let local_state_contract_pointer = ContractRef::TURef(TURef::new(
        local_state_uref.addr(),
        contract_ffi::uref::AccessRights::READ,
    ));

    // call do_nothing_stored
    runtime::call_contract::<_, ()>(local_state_contract_pointer.clone(), &(), &vec![]);
}
