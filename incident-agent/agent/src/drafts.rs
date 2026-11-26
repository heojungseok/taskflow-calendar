use std::io;
use std::path::Path;

use crate::atomic;

pub(crate) fn save(dir: &Path, incident: u64, markdown: &str) -> io::Result<()> {
    atomic::write(dir, &format!("incident-{incident}.md"), markdown.as_bytes())
}

#[cfg(test)]
mod tests {
    use std::fs;

    use super::*;

    #[test]
    fn t38_incident_id_md로_저장하고_다시_저장하면_덮어쓴다() {
        let dir = tempfile::tempdir().unwrap();
        save(dir.path(), 7, "첫 초안").unwrap();
        save(dir.path(), 7, "고친 초안").unwrap();
        let text = fs::read_to_string(dir.path().join("incident-7.md")).unwrap();
        assert_eq!(text, "고친 초안");
        assert_eq!(fs::read_dir(dir.path()).unwrap().count(), 1);
    }

    #[test]
    fn t38_폴더가_없으면_만든다() {
        let dir = tempfile::tempdir().unwrap();
        let nested = dir.path().join("drafts");
        save(&nested, 1, "x").unwrap();
        assert!(nested.join("incident-1.md").exists());
    }
}
