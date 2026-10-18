use crate::{
    compatibility::{Requirements, CAPABILITIES},
    Error, Result,
};

#[derive(Clone, Copy)]
pub(crate) enum ValidationTarget {
    Installer,
    OdOta,
}

impl ValidationTarget {
    pub(super) fn validate_schema(self, schema: u32) -> Result<()> {
        if matches!(self, Self::OdOta) && schema > super::MANIFEST_SCHEMA {
            return Err(Error::new(
                "OTA_USB_REQUIRED",
                "Формат релиза не поддерживается установленной службой OTA. Обновите VoyahTune через USB.",
            ));
        }
        super::validate_schema(schema)
    }

    pub(crate) fn validate_requirements(self, requirements: &Requirements) -> Result<()> {
        match self {
            Self::Installer => requirements.validate(),
            Self::OdOta => {
                if let Some(capability) = requirements
                    .required_capabilities
                    .iter()
                    .find(|capability| !CAPABILITIES.contains(&capability.as_str()))
                {
                    return Err(Error::new(
                        "OTA_USB_REQUIRED",
                        "Релиз требует новые возможности службы OTA. Обновите VoyahTune через USB.",
                    )
                    .detail(capability));
                }
                Ok(())
            }
        }
    }
}
