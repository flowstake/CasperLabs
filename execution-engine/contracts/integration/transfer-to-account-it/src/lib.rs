#![no_std]

extern crate contract_ffi;

use contract_ffi::contract_api::{runtime, system, Error};
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;
use contract_ffi::value::account::PublicKey;
use contract_ffi::value::U512;

#[no_mangle]
pub extern "C" fn call() {
    let account_addr: [u8; 32] = runtime::get_arg(0)
        .unwrap_or_revert_with(Error::MissingArgument)
        .unwrap_or_revert_with(Error::InvalidArgument);
    let transfer_amount: u32 = runtime::get_arg(1)
        .unwrap_or_revert_with(Error::MissingArgument)
        .unwrap_or_revert_with(Error::InvalidArgument);

    let public_key = PublicKey::new(account_addr);
    let amount = U512::from(transfer_amount);

    system::transfer_to_account(public_key, amount).unwrap_or_revert_with(Error::User(1));
}
