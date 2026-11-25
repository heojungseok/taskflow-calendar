use std::path::PathBuf;

use totp_rs::{Builder, Secret, Totp};

pub struct Setup {
    pub(crate) results_dir: PathBuf,
    pub(crate) lock_file: PathBuf,
    pub(crate) rolled_back_file: PathBuf,
    pub(crate) guard_file: PathBuf,
    pub(crate) used_dir: PathBuf,
    pub(crate) deploy_journal: PathBuf,
    pub(crate) totp: Totp,
}

impl Setup {
    pub fn new(
        results_dir: PathBuf,
        state_dir: PathBuf,
        deploy_state_dir: PathBuf,
        totp_secret_base32: &str,
    ) -> Result<Self, String> {
        let secret = Secret::try_from_base32(totp_secret_base32)
            .map_err(|_| "invalid totp secret".to_owned())?;
        let totp = Builder::new()
            .with_secret(secret)
            .build()
            .map_err(|_| "invalid totp configuration".to_owned())?;
        Ok(Self {
            results_dir,
            used_dir: state_dir.join("used"),
            guard_file: state_dir.join("totp-guard"),
            lock_file: state_dir.join("executor.lock"),
            rolled_back_file: state_dir.join("rolled-back"),
            deploy_journal: deploy_state_dir.join("journal.tsv"),
            totp,
        })
    }
}
