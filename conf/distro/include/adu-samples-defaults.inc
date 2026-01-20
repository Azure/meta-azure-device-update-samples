# Default configuration for meta-azure-device-update-samples
# These can be overridden in local.conf or distro config

# Base ADU software version for sample images
# v1 = BASE_ADU_SOFTWARE_VERSION
# v2 = BASE_ADU_SOFTWARE_VERSION + 1 (e.g., 1.0.1)
# v3 = BASE_ADU_SOFTWARE_VERSION + 2 (e.g., 1.0.2)
BASE_ADU_SOFTWARE_VERSION ??= "1.0.0"

# ADU manifest configuration
ADU_PROVIDER ??= "Contoso"
ADU_MODEL ??= "Sample-Device"
ADU_COMPATIBILITY_HARDWARE ??= "1.0"

# SWUpdate signing configuration
# Override these in your BSP layer or local.conf
ADUC_PRIVATE_KEY ??= "${TOPDIR}/conf/swupdate-signing/priv.pem"
ADUC_PRIVATE_KEY_PASSWORD ??= "${TOPDIR}/conf/swupdate-signing/priv.pass"

# Enable delta update feature by default in samples
WITH_FEATURE_DELTA_UPDATE ??= "1"
