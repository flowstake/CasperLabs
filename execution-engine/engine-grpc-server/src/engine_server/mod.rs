use std::collections::{BTreeMap, BTreeSet};
use std::convert::TryFrom;
use std::convert::TryInto;
use std::fmt::Debug;
use std::io::ErrorKind;
use std::marker::{Send, Sync};
use std::time::Instant;

use grpc::SingleResponse;

use contract_ffi::key::Key;
use contract_ffi::value::account::{BlockTime, PublicKey};
use contract_ffi::value::{ProtocolVersion, U512};
use engine_core::engine_state::error::Error as EngineError;
use engine_core::engine_state::execution_result::ExecutionResult;
use engine_core::engine_state::genesis::{GenesisConfig, GenesisResult};
use engine_core::engine_state::EngineState;
use engine_core::execution::{Executor, WasmiExecutor};
use engine_core::tracking_copy::QueryResult;
use engine_shared::logging;
use engine_shared::logging::{log_duration, log_info};
use engine_shared::newtypes::{Blake2bHash, CorrelationId};
use engine_storage::global_state::{CommitResult, StateProvider};
use engine_wasm_prep::{Preprocessor, WasmiPreprocessor};

use self::ipc_grpc::ExecutionEngineService;
use self::mappings::*;
use engine_core::engine_state::upgrade::{UpgradeConfig, UpgradeResult};
use engine_shared::logging::log_level::LogLevel;

pub mod ipc;
pub mod ipc_grpc;
pub mod mappings;
pub mod state;
pub mod transforms;

const EXPECTED_PUBLIC_KEY_LENGTH: usize = 32;

const METRIC_DURATION_COMMIT: &str = "commit_duration";
const METRIC_DURATION_EXEC: &str = "exec_duration";
const METRIC_DURATION_QUERY: &str = "query_duration";
const METRIC_DURATION_VALIDATE: &str = "validate_duration";
const METRIC_DURATION_GENESIS: &str = "genesis_duration";
const METRIC_DURATION_UPGRADE: &str = "upgrade_duration";

const TAG_RESPONSE_COMMIT: &str = "commit_response";
const TAG_RESPONSE_EXEC: &str = "exec_response";
const TAG_RESPONSE_QUERY: &str = "query_response";
const TAG_RESPONSE_VALIDATE: &str = "validate_response";
const TAG_RESPONSE_GENESIS: &str = "genesis_response";
const TAG_RESPONSE_UPGRADE: &str = "upgrade_response";

const DEFAULT_PROTOCOL_VERSION: ProtocolVersion = ProtocolVersion::V1_0_0;

// Idea is that Engine will represent the core of the execution engine project.
// It will act as an entry point for execution of Wasm binaries.
// Proto definitions should be translated into domain objects when Engine's API
// is invoked. This way core won't depend on casperlabs-engine-grpc-server
// (outer layer) leading to cleaner design.
impl<S> ipc_grpc::ExecutionEngineService for EngineState<S>
where
    S: StateProvider,
    EngineError: From<S::Error>,
    S::Error: Into<engine_core::execution::Error> + Debug,
{
    fn query(
        &self,
        _request_options: ::grpc::RequestOptions,
        query_request: ipc::QueryRequest,
    ) -> grpc::SingleResponse<ipc::QueryResponse> {
        let start = Instant::now();
        let correlation_id = CorrelationId::new();
        // TODO: don't unwrap
        let state_hash: Blake2bHash = query_request.get_state_hash().try_into().unwrap();

        let mut tracking_copy = match self.tracking_copy(state_hash) {
            Err(storage_error) => {
                let mut result = ipc::QueryResponse::new();
                let error = format!("Error during checkout out Trie: {:?}", storage_error);
                logging::log_error(&error);
                result.set_failure(error);
                log_duration(
                    correlation_id,
                    METRIC_DURATION_QUERY,
                    "tracking_copy_error",
                    start.elapsed(),
                );
                return grpc::SingleResponse::completed(result);
            }
            Ok(None) => {
                let mut result = ipc::QueryResponse::new();
                let error = format!("Root not found: {:?}", state_hash);
                logging::log_warning(&error);
                result.set_failure(error);
                log_duration(
                    correlation_id,
                    METRIC_DURATION_QUERY,
                    "tracking_copy_root_not_found",
                    start.elapsed(),
                );
                return grpc::SingleResponse::completed(result);
            }
            Ok(Some(tracking_copy)) => tracking_copy,
        };

        let key = match query_request.get_base_key().try_into() {
            Err(ParsingError(err_msg)) => {
                logging::log_error(&err_msg);
                let mut result = ipc::QueryResponse::new();
                result.set_failure(err_msg);
                log_duration(
                    correlation_id,
                    METRIC_DURATION_QUERY,
                    "key_parsing_error",
                    start.elapsed(),
                );
                return grpc::SingleResponse::completed(result);
            }
            Ok(key) => key,
        };

        let path = query_request.get_path();

        let response = match tracking_copy.query(correlation_id, key, path) {
            Err(err) => {
                let mut result = ipc::QueryResponse::new();
                let error = format!("{:?}", err);
                logging::log_error(&error);
                result.set_failure(error);
                result
            }
            Ok(QueryResult::ValueNotFound(full_path)) => {
                let mut result = ipc::QueryResponse::new();
                let error = format!("Value not found: {:?}", full_path);
                logging::log_warning(&error);
                result.set_failure(error);
                result
            }
            Ok(QueryResult::Success(value)) => {
                let mut result = ipc::QueryResponse::new();
                result.set_success(value.into());
                result
            }
        };

        log_duration(
            correlation_id,
            METRIC_DURATION_QUERY,
            TAG_RESPONSE_QUERY,
            start.elapsed(),
        );

        grpc::SingleResponse::completed(response)
    }

    fn execute(
        &self,
        _request_options: ::grpc::RequestOptions,
        exec_request: ipc::ExecuteRequest,
    ) -> grpc::SingleResponse<ipc::ExecuteResponse> {
        let start = Instant::now();
        let correlation_id = CorrelationId::new();

        let protocol_version = exec_request.get_protocol_version().into();

        // TODO: don't unwrap
        let prestate_hash: Blake2bHash = exec_request.get_parent_state_hash().try_into().unwrap();

        let blocktime = BlockTime(exec_request.get_block_time());

        // TODO: don't unwrap
        let wasm_costs = self.wasm_costs(protocol_version).unwrap().unwrap();

        let deploys = exec_request.get_deploys();

        let preprocessor: WasmiPreprocessor = WasmiPreprocessor::new(wasm_costs);

        let executor = WasmiExecutor;

        let deploys_result: Result<Vec<ipc::DeployResult>, ipc::RootNotFound> = execute_deploys(
            &self,
            &executor,
            &preprocessor,
            prestate_hash,
            blocktime,
            deploys,
            protocol_version,
            correlation_id,
        );

        let exec_response = match deploys_result {
            Ok(deploy_results) => {
                let mut exec_response = ipc::ExecuteResponse::new();
                let mut exec_result = ipc::ExecResult::new();
                exec_result.set_deploy_results(protobuf::RepeatedField::from_vec(deploy_results));
                exec_response.set_success(exec_result);
                exec_response
            }
            Err(error) => {
                logging::log_error("deploy results error: RootNotFound");
                let mut exec_response = ipc::ExecuteResponse::new();
                exec_response.set_missing_parent(error);
                exec_response
            }
        };

        log_duration(
            correlation_id,
            METRIC_DURATION_EXEC,
            TAG_RESPONSE_EXEC,
            start.elapsed(),
        );

        grpc::SingleResponse::completed(exec_response)
    }

    fn commit(
        &self,
        _request_options: ::grpc::RequestOptions,
        commit_request: ipc::CommitRequest,
    ) -> grpc::SingleResponse<ipc::CommitResponse> {
        let start = Instant::now();
        let correlation_id = CorrelationId::new();

        // TODO
        let protocol_version = {
            let protocol_version = commit_request.get_protocol_version().into();
            if protocol_version < DEFAULT_PROTOCOL_VERSION {
                DEFAULT_PROTOCOL_VERSION
            } else {
                protocol_version
            }
        };

        // Acquire pre-state hash
        let pre_state_hash: Blake2bHash = match commit_request.get_prestate_hash().try_into() {
            Err(_) => {
                let error_message = "Could not parse pre-state hash".to_string();
                logging::log_error(&error_message);

                let err = {
                    let mut tmp = ipc::PostEffectsError::new();
                    tmp.set_message(error_message);
                    tmp
                };
                let mut commit_response = ipc::CommitResponse::new();
                commit_response.set_failed_transform(err);
                return SingleResponse::completed(commit_response);
            }
            Ok(hash) => hash,
        };

        // Acquire commit transforms
        let transforms: CommitTransforms = match commit_request.get_effects().try_into() {
            Err(ParsingError(error_message)) => {
                logging::log_error(&error_message);

                let err = {
                    let mut tmp = ipc::PostEffectsError::new();
                    tmp.set_message(error_message);
                    tmp
                };
                let mut commit_response = ipc::CommitResponse::new();
                commit_response.set_failed_transform(err);
                return SingleResponse::completed(commit_response);
            }
            Ok(transforms) => transforms,
        };

        // "Apply" effects to global state
        let commit_response = {
            let mut ret = ipc::CommitResponse::new();

            match self.apply_effect(
                correlation_id,
                protocol_version,
                pre_state_hash,
                transforms.value(),
            ) {
                Ok(CommitResult::Success {
                    state_root,
                    bonded_validators,
                }) => {
                    let properties = {
                        let mut tmp = BTreeMap::new();
                        tmp.insert("post-state-hash".to_string(), format!("{:?}", state_root));
                        tmp.insert("success".to_string(), true.to_string());
                        tmp
                    };
                    logging::log_details(
                        LogLevel::Info,
                        "effects applied; new state hash is: {post-state-hash}".to_owned(),
                        properties,
                    );

                    let bonds = bonded_validators.into_iter().map(Into::into).collect();
                    let commit_result = {
                        let mut tmp = ipc::CommitResult::new();
                        tmp.set_poststate_hash(state_root.to_vec());
                        tmp.set_bonded_validators(bonds);
                        tmp
                    };
                    ret.set_success(commit_result);
                }
                Ok(CommitResult::RootNotFound) => {
                    logging::log_warning("RootNotFound");

                    let root_not_found = {
                        let mut tmp = ipc::RootNotFound::new();
                        tmp.set_hash(pre_state_hash.to_vec());
                        tmp
                    };
                    ret.set_missing_prestate(root_not_found);
                }
                Ok(CommitResult::KeyNotFound(key)) => {
                    logging::log_warning("KeyNotFound");

                    ret.set_key_not_found(key.into());
                }
                Ok(CommitResult::TypeMismatch(type_mismatch)) => {
                    logging::log_warning("TypeMismatch");

                    ret.set_type_mismatch(type_mismatch.into());
                }
                Err(error) => {
                    let log_message = format!("State error {:?} when applying transforms", error);
                    logging::log_error(&log_message);

                    let err = {
                        let mut tmp = ipc::PostEffectsError::new();
                        tmp.set_message(format!("{:?}", error));
                        tmp
                    };
                    ret.set_failed_transform(err);
                }
            }

            ret
        };

        log_duration(
            correlation_id,
            METRIC_DURATION_COMMIT,
            TAG_RESPONSE_COMMIT,
            start.elapsed(),
        );

        grpc::SingleResponse::completed(commit_response)
    }

    fn validate(
        &self,
        _request_options: ::grpc::RequestOptions,
        validate_request: ipc::ValidateRequest,
    ) -> grpc::SingleResponse<ipc::ValidateResponse> {
        let start = Instant::now();
        let correlation_id = CorrelationId::new();

        let module = wabt::Module::read_binary(
            validate_request.wasm_code,
            &wabt::ReadBinaryOptions::default(),
        )
        .and_then(|x| x.validate());

        log_duration(
            correlation_id,
            METRIC_DURATION_VALIDATE,
            "module",
            start.elapsed(),
        );

        let validate_result = match module {
            Ok(_) => {
                let mut validate_result = ipc::ValidateResponse::new();
                validate_result.set_success(ipc::ValidateResponse_ValidateSuccess::new());
                validate_result
            }
            Err(cause) => {
                let cause_msg = cause.to_string();
                logging::log_error(&cause_msg);

                let mut validate_result = ipc::ValidateResponse::new();
                validate_result.set_failure(cause_msg);
                validate_result
            }
        };

        log_duration(
            correlation_id,
            METRIC_DURATION_VALIDATE,
            TAG_RESPONSE_VALIDATE,
            start.elapsed(),
        );

        grpc::SingleResponse::completed(validate_result)
    }

    #[allow(dead_code)]
    fn run_genesis(
        &self,
        _request_options: ::grpc::RequestOptions,
        genesis_request: ipc::GenesisRequest,
    ) -> ::grpc::SingleResponse<ipc::GenesisResponse> {
        let start = Instant::now();
        let correlation_id = CorrelationId::new();

        let genesis_account_addr = {
            let address = genesis_request.get_address();
            if address.len() != 32 {
                let err_msg =
                    "genesis account public key has to be exactly 32 bytes long.".to_string();
                logging::log_error(&err_msg);

                let mut genesis_response = ipc::GenesisResponse::new();
                let mut genesis_deploy_error = ipc::GenesisDeployError::new();
                genesis_deploy_error.set_message(err_msg);
                genesis_response.set_failed_deploy(genesis_deploy_error);

                log_duration(
                    correlation_id,
                    METRIC_DURATION_GENESIS,
                    TAG_RESPONSE_GENESIS,
                    start.elapsed(),
                );

                return grpc::SingleResponse::completed(genesis_response);
            }

            let mut ret = [0u8; 32];
            ret.clone_from_slice(address);
            ret
        };

        let initial_motes: U512 = match genesis_request.get_initial_motes().try_into() {
            Ok(initial_motes) => initial_motes,
            Err(err) => {
                let err_msg = format!("{:?}", err);
                logging::log_error(&err_msg);

                let mut genesis_response = ipc::GenesisResponse::new();
                let mut genesis_deploy_error = ipc::GenesisDeployError::new();
                genesis_deploy_error.set_message(err_msg);
                genesis_response.set_failed_deploy(genesis_deploy_error);

                log_duration(
                    correlation_id,
                    METRIC_DURATION_GENESIS,
                    TAG_RESPONSE_GENESIS,
                    start.elapsed(),
                );

                return grpc::SingleResponse::completed(genesis_response);
            }
        };

        let mint_code_bytes = genesis_request.get_mint_code().get_code();

        let proof_of_stake_code_bytes = genesis_request.get_proof_of_stake_code().get_code();

        let genesis_validators_result: Result<Vec<(PublicKey, U512)>, MappingError> =
            genesis_request
                .get_genesis_validators()
                .iter()
                .map(TryInto::try_into)
                .collect();

        let genesis_validators = match genesis_validators_result {
            Ok(validators) => validators,
            Err(error) => {
                logging::log_error(&error.to_string());

                let genesis_deploy_error = {
                    let mut tmp = ipc::GenesisDeployError::new();
                    tmp.set_message(error.to_string());
                    tmp
                };
                let mut genesis_response = ipc::GenesisResponse::new();
                genesis_response.set_failed_deploy(genesis_deploy_error);

                log_duration(
                    correlation_id,
                    METRIC_DURATION_GENESIS,
                    TAG_RESPONSE_GENESIS,
                    start.elapsed(),
                );

                return grpc::SingleResponse::completed(genesis_response);
            }
        };

        let protocol_version = genesis_request.get_protocol_version().into();

        let genesis_response = {
            let mut genesis_response = ipc::GenesisResponse::new();

            match self.commit_genesis(
                correlation_id,
                genesis_account_addr,
                initial_motes,
                mint_code_bytes,
                proof_of_stake_code_bytes,
                genesis_validators,
                protocol_version,
            ) {
                Ok(GenesisResult::Success {
                    post_state_hash,
                    effect,
                }) => {
                    let success_message = format!("run_genesis successful: {}", post_state_hash);
                    log_info(&success_message);

                    let mut genesis_result = ipc::GenesisResult::new();
                    genesis_result.set_poststate_hash(post_state_hash.to_vec());
                    genesis_result.set_effect(effect.into());
                    genesis_response.set_success(genesis_result);
                }
                Ok(genesis_result) => {
                    let err_msg = genesis_result.to_string();
                    logging::log_error(&err_msg);

                    let mut genesis_deploy_error = ipc::GenesisDeployError::new();
                    genesis_deploy_error.set_message(err_msg);
                    genesis_response.set_failed_deploy(genesis_deploy_error);
                }
                Err(err) => {
                    let err_msg = err.to_string();
                    logging::log_error(&err_msg);

                    let mut genesis_deploy_error = ipc::GenesisDeployError::new();
                    genesis_deploy_error.set_message(err_msg);
                    genesis_response.set_failed_deploy(genesis_deploy_error);
                }
            }

            genesis_response
        };

        log_duration(
            correlation_id,
            METRIC_DURATION_GENESIS,
            TAG_RESPONSE_GENESIS,
            start.elapsed(),
        );

        grpc::SingleResponse::completed(genesis_response)
    }

    fn run_genesis_with_chainspec(
        &self,
        _request_options: ::grpc::RequestOptions,
        genesis_config: ipc::ChainSpec_GenesisConfig,
    ) -> ::grpc::SingleResponse<ipc::GenesisResponse> {
        let correlation_id = CorrelationId::new();

        let genesis_config: GenesisConfig = match genesis_config.try_into() {
            Ok(genesis_config) => genesis_config,
            Err(error) => {
                let err_msg = error.to_string();
                logging::log_error(&err_msg);

                let mut genesis_response = ipc::GenesisResponse::new();
                let mut genesis_deploy_error = ipc::GenesisDeployError::new();
                genesis_deploy_error.set_message(err_msg);
                genesis_response.set_failed_deploy(genesis_deploy_error);
                return grpc::SingleResponse::completed(genesis_response);
            }
        };

        let genesis_response =
            match self.commit_genesis_with_chainspec(correlation_id, genesis_config) {
                Ok(GenesisResult::Success {
                    post_state_hash,
                    effect,
                }) => {
                    let success_message =
                        format!("run_genesis_with_chainspec successful: {}", post_state_hash);
                    log_info(&success_message);

                    let mut genesis_response = ipc::GenesisResponse::new();
                    let mut genesis_result = ipc::GenesisResult::new();
                    genesis_result.set_poststate_hash(post_state_hash.to_vec());
                    genesis_result.set_effect(effect.into());
                    genesis_response.set_success(genesis_result);
                    genesis_response
                }
                Ok(genesis_result) => {
                    let err_msg = genesis_result.to_string();
                    logging::log_error(&err_msg);

                    let mut genesis_response = ipc::GenesisResponse::new();
                    let mut genesis_deploy_error = ipc::GenesisDeployError::new();
                    genesis_deploy_error.set_message(err_msg);
                    genesis_response.set_failed_deploy(genesis_deploy_error);
                    genesis_response
                }
                Err(err) => {
                    let err_msg = err.to_string();
                    logging::log_error(&err_msg);

                    let mut genesis_response = ipc::GenesisResponse::new();
                    let mut genesis_deploy_error = ipc::GenesisDeployError::new();
                    genesis_deploy_error.set_message(err_msg);
                    genesis_response.set_failed_deploy(genesis_deploy_error);
                    genesis_response
                }
            };

        grpc::SingleResponse::completed(genesis_response)
    }

    fn upgrade(
        &self,
        _request_options: ::grpc::RequestOptions,
        upgrade_request: ipc::UpgradeRequest,
    ) -> ::grpc::SingleResponse<ipc::UpgradeResponse> {
        let start = Instant::now();
        let correlation_id = CorrelationId::new();

        let upgrade_config: UpgradeConfig = match upgrade_request.try_into() {
            Ok(upgrade_config) => upgrade_config,
            Err(error) => {
                let err_msg = error.to_string();
                logging::log_error(&err_msg);

                let mut upgrade_deploy_error = ipc::UpgradeDeployError::new();
                upgrade_deploy_error.set_message(err_msg);
                let mut upgrade_response = ipc::UpgradeResponse::new();
                upgrade_response.set_failed_deploy(upgrade_deploy_error);

                log_duration(
                    correlation_id,
                    METRIC_DURATION_UPGRADE,
                    TAG_RESPONSE_UPGRADE,
                    start.elapsed(),
                );

                return grpc::SingleResponse::completed(upgrade_response);
            }
        };

        let upgrade_response = match self.commit_upgrade(correlation_id, upgrade_config) {
            Ok(UpgradeResult::Success {
                post_state_hash,
                effect,
            }) => {
                let success_message = format!("upgrade successful: {}", post_state_hash);
                log_info(&success_message);

                let mut upgrade_result = ipc::UpgradeResult::new();
                upgrade_result.set_post_state_hash(post_state_hash.to_vec());
                upgrade_result.set_effect(effect.into());

                let mut ret = ipc::UpgradeResponse::new();
                ret.set_success(upgrade_result);
                ret
            }
            Ok(upgrade_result) => {
                let err_msg = upgrade_result.to_string();
                logging::log_error(&err_msg);

                let mut upgrade_deploy_error = ipc::UpgradeDeployError::new();
                upgrade_deploy_error.set_message(err_msg);

                let mut ret = ipc::UpgradeResponse::new();
                ret.set_failed_deploy(upgrade_deploy_error);
                ret
            }
            Err(err) => {
                let err_msg = err.to_string();
                logging::log_error(&err_msg);

                let mut upgrade_deploy_error = ipc::UpgradeDeployError::new();
                upgrade_deploy_error.set_message(err_msg);

                let mut ret = ipc::UpgradeResponse::new();
                ret.set_failed_deploy(upgrade_deploy_error);
                ret
            }
        };

        log_duration(
            correlation_id,
            METRIC_DURATION_UPGRADE,
            TAG_RESPONSE_UPGRADE,
            start.elapsed(),
        );

        grpc::SingleResponse::completed(upgrade_response)
    }
}

#[allow(clippy::too_many_arguments)]
fn execute_deploys<A, S, E, P>(
    engine_state: &EngineState<S>,
    executor: &E,
    preprocessor: &P,
    prestate_hash: Blake2bHash,
    blocktime: BlockTime,
    deploys: &[ipc::DeployItem],
    protocol_version: ProtocolVersion,
    correlation_id: CorrelationId,
) -> Result<Vec<ipc::DeployResult>, ipc::RootNotFound>
where
    S: StateProvider,
    E: Executor<A>,
    P: Preprocessor<A>,
    EngineError: From<S::Error>,
    S::Error: Into<engine_core::execution::Error>,
{
    // We want to treat RootNotFound error differently b/c it should short-circuit
    // the execution of ALL deploys within the block. This is because all of them
    // share the same prestate and all of them would fail.
    // Iterator (Result<_, _> + collect()) will short circuit the execution
    // when run_deploy returns Err.
    deploys
        .iter()
        .map(|deploy| {
            let session = match deploy.get_session().to_owned().payload {
                Some(payload) => payload.into(),
                None => {
                    return Ok(
                        ExecutionResult::precondition_failure(EngineError::DeployError).into(),
                    )
                }
            };

            let payment = match deploy.get_payment().to_owned().payload {
                Some(payload) => payload.into(),
                None => {
                    return Ok(
                        ExecutionResult::precondition_failure(EngineError::DeployError).into(),
                    )
                }
            };

            let address = {
                let address_len = deploy.address.len();
                if address_len != EXPECTED_PUBLIC_KEY_LENGTH {
                    let err = EngineError::InvalidPublicKeyLength {
                        expected: EXPECTED_PUBLIC_KEY_LENGTH,
                        actual: address_len,
                    };
                    let failure = ExecutionResult::precondition_failure(err);
                    return Ok(failure.into());
                }
                let mut dest = [0; EXPECTED_PUBLIC_KEY_LENGTH];
                dest.copy_from_slice(&deploy.address);
                Key::Account(dest)
            };

            // Parse all authorization keys from IPC into a vector
            let authorization_keys: BTreeSet<PublicKey> = {
                let maybe_keys: Result<BTreeSet<_>, EngineError> = deploy
                    .authorization_keys
                    .iter()
                    .map(|key_bytes| {
                        // Try to convert an element of bytes into a possibly
                        // valid PublicKey with error handling
                        PublicKey::try_from(key_bytes.as_slice()).map_err(|_| {
                            EngineError::InvalidPublicKeyLength {
                                expected: EXPECTED_PUBLIC_KEY_LENGTH,
                                actual: key_bytes.len(),
                            }
                        })
                    })
                    .collect();

                match maybe_keys {
                    Ok(keys) => keys,
                    Err(error) => return Ok(ExecutionResult::precondition_failure(error).into()),
                }
            };

            let deploy_hash = {
                let mut buff = [0u8; 32];
                let hash_slice = deploy.get_deploy_hash();
                buff.copy_from_slice(hash_slice);
                buff
            };

            engine_state
                .deploy(
                    session,
                    payment,
                    address,
                    authorization_keys,
                    blocktime,
                    deploy_hash,
                    prestate_hash,
                    protocol_version,
                    correlation_id,
                    executor,
                    preprocessor,
                )
                .map(Into::into)
                .map_err(Into::into)
        })
        .collect()
}

// Helper method which returns single DeployResult that is set to be a
// WasmError.
pub fn new<E: ExecutionEngineService + Sync + Send + 'static>(
    socket: &str,
    e: E,
) -> grpc::ServerBuilder {
    let socket_path = std::path::Path::new(socket);

    if let Err(e) = std::fs::remove_file(socket_path) {
        if e.kind() != ErrorKind::NotFound {
            panic!("failed to remove old socket file: {:?}", e);
        }
    }

    let mut server = grpc::ServerBuilder::new_plain();
    server.http.set_unix_addr(socket.to_owned()).unwrap();
    server.http.set_cpu_pool_threads(1);
    server.add_service(ipc_grpc::ExecutionEngineServiceServer::new_service_def(e));
    server
}
