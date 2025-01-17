#![no_std]

#[macro_use]
extern crate alloc;
extern crate contract_ffi;

use contract_ffi::contract_api::ContractRef;
use contract_ffi::contract_api::{account, runtime, system, Error};
use contract_ffi::key::Key;
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;
use contract_ffi::value::account::PurseId;
use contract_ffi::value::U512;

fn purse_to_key(p: PurseId) -> Key {
    Key::URef(p.value())
}

const POS_BOND: &str = "bond";
const POS_UNBOND: &str = "unbond";

fn bond(pos: ContractRef, amount: U512, source: PurseId) {
    runtime::call_contract::<_, ()>(
        pos,
        &(POS_BOND, amount, source),
        &vec![purse_to_key(source)],
    );
}

fn unbond(pos: ContractRef, amount: Option<U512>) {
    runtime::call_contract::<_, ()>(pos, &(POS_UNBOND, amount), &vec![]);
}

#[no_mangle]
pub extern "C" fn call() {
    let pos_pointer = system::get_proof_of_stake();
    let amount: U512 = runtime::get_arg(0)
        .unwrap_or_revert_with(Error::MissingArgument)
        .unwrap_or_revert_with(Error::InvalidArgument);
    bond(pos_pointer.clone(), amount, account::get_main_purse());
    unbond(pos_pointer, Some(amount + 1));
}
