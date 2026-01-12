# meta-azure-device-update-samples

**Board-agnostic layer for Azure Device Update demonstration, testing, and samples**

## Overview

This Yocto/OpenEmbedded layer provides **platform-independent** reference implementations for demonstrating Azure Device Update (ADU) capabilities. It separates delta generation, versioned update packages, and testing workflows from board-specific configurations, making it easy to adapt ADU to any hardware platform.

## Purpose

This layer enables you to:

- ✅ **Generate delta updates** - Create efficient binary diff packages between SWUpdate images
- ✅ **Build versioned samples** - Three pre-configured update images (v1, v2, v3) for testing update workflows
- ✅ **Create import manifests** - Automatically generate ADU-compatible manifests for Azure IoT Hub
- ✅ **Demonstrate ADU features** - Complete examples showing full-image updates, delta updates, and versioning
- ✅ **Test update scenarios** - Validate update flows without requiring physical hardware

## What This Layer Does NOT Provide

This layer is **board-agnostic**. Your BSP (Board Support Package) layer must provide:

- ❌ Platform-specific base images (rootfs, kernel, boot files)
- ❌ Hardware-specific boot configurations (U-Boot, GRUB, device tree)
- ❌ Board-specific partition layouts and storage strategies
- ❌ Device-specific persistence mechanisms

**Example BSP Layer**: `meta-raspberrypi-adu` provides these for Raspberry Pi 4.

---

## Quick Start

### 1. Add Layer to Your Build

The layer is automatically included if you're using the `iot-hub-device-update-yocto` repository. For manual setup:

```bash
# Ensure dependencies are present
bitbake-layers add-layer ../meta-azure-device-update
bitbake-layers add-layer ../meta-iot-hub-device-update-delta
bitbake-layers add-layer ../meta-swupdate
bitbake-layers add-layer ../meta-clang

# Add samples layer
bitbake-layers add-layer ../meta-azure-device-update-samples
```

### 2. Configure Virtual Provider

Set your BSP's base image as the provider in `conf/local.conf`:

```bitbake
# Tell BitBake which recipe provides the base image
PREFERRED_PROVIDER_virtual/adu-base-image = "adu-base-image"
```

Your BSP layer must have a base image recipe that provides this:

```bitbake
# In your BSP layer: recipes-core/images/adu-base-image.bb
PROVIDES = "virtual/adu-base-image"
```

### 3. Build Sample Update Images

```bash
# Using the build.sh script (from iot-hub-device-update-yocto)
cd ~/adu_yocto/iot-hub-device-update-yocto
./scripts/build.sh --build adu-update-image-v1,adu-update-image-v2,adu-update-image-v3

# Or build directly with BitBake
bitbake adu-update-image-v1 adu-update-image-v2 adu-update-image-v3
# Build all three versioned sample images
bitbake adu-update-image-v1 adu-update-image-v2 adu-update-image-v3
```

### 4. Generate Deltas

```bash
# Generate delta files between versions
bitbake adu-delta-image
```

Output files in `tmp/deploy/images/<MACHINE>/`:
- `adu-delta-v1-to-v2.diff` (~600 bytes for minimal change)
- `adu-delta-v2-to-v3.diff`
- `adu-delta-v1-to-v3.diff`

### 5. Generate Import Manifests

```bash
# Create Azure import manifests
bitbake adu-import-manifests
```

Output: JSON manifest files ready to import into Azure Portal

## Configuration Options

Override these in `local.conf`:

```bash
# Software version for sample images
BASE_ADU_SOFTWARE_VERSION = "2.5.0"

# ADU manifest settings
ADU_PROVIDER = "YourCompany"
ADU_MODEL = "YourDevice"

# SWUpdate signing keys
ADUC_PRIVATE_KEY = "/path/to/priv.pem"
ADUC_PRIVATE_KEY_PASSWORD = "/path/to/priv.pass"
```

## Architecture

```
┌──────────────────────────────────────────────┐
│  Your BSP Layer (e.g., meta-raspberrypi-adu)│
│  Provides: virtual/adu-base-image            │
│  Output: adu-base-image.ext4.gz              │
└──────────────┬───────────────────────────────┘
               │ provides rootfs content
               ↓
┌──────────────────────────────────────────────┐
│  meta-azure-device-update-samples            │
│                                              │
│  adu-update-image-v1.bb                     │
│  - Wraps base in .swu (version 1.0.0)       │
│  - Output: adu-update-image-v1.swu          │
│                                              │
│  adu-update-image-v2.bb                     │
│  - Wraps base in .swu (version 1.0.1)       │
│                                              │
│  adu-delta-image.bb                         │
│  - Generates deltas between .swu files      │
│  - Output: adu-delta-v1-to-v2.diff          │
└──────────────────────────────────────────────┘
```

## Recipes Provided

### Sample Images
- `adu-update-image-v1.bb` - Base version (1.0.0)
- `adu-update-image-v2.bb` - Incremental update (1.0.1)
- `adu-update-image-v3.bb` - Second incremental (1.0.2)

### Delta Generation
- `adu-delta-image.bb` - Generates binary diffs between .swu packages

### Manifests
- `adu-import-manifests.bb` - Creates Azure Device Update import manifests

### Classes
- `adu-timestamp-check.bbclass` - Timestamp validation for delta files

## Version Strategy

Versions auto-increment based on `BASE_ADU_SOFTWARE_VERSION`:

```
BASE_ADU_SOFTWARE_VERSION = "1.0.0"
  ↓
v1: 1.0.0  (base)
v2: 1.0.1  (BASE + 1 patch)
v3: 1.0.2  (BASE + 2 patches)
```

Each version contains a minimal change (single file update) to demonstrate efficient delta generation.

## Testing

Verify delta generation works:

```bash
# Check delta file exists and is small
ls -lh tmp/deploy/images/<MACHINE>/adu-delta-v1-to-v2.diff

# Expected: ~600 bytes for minimal change between 240MB images (99.9% savings)
```

---

## Demonstration Scenarios

This layer enables multiple Azure Device Update demonstration workflows:

### Demo 1: Full-Image Update (Basic)
**Requirements**: ADU agent + device connection
**What it demonstrates**: Installing a complete update package via Azure IoT Hub

**Steps**:
1. Flash device with base image containing v1
2. Upload `adu-update-image-v2.swu` and manifest to Azure Portal
3. Deploy update to device via IoT Hub
4. Device downloads and installs v2.swu (full package ~240MB)

**Layer artifacts used**: `adu-update-image-v2.swu`

---

### Demo 2: Delta Update (Advanced)
**Requirements**: ADU agent + delta support enabled + sufficient storage
**What it demonstrates**: Efficient updates using binary diff patches

**Steps**:
1. Device running v1 (1.0.0)
2. Upload `adu-delta-v1-to-v2.diff` and manifest to Azure
3. Deploy delta update to device
4. Device downloads tiny diff (~600 bytes vs 240MB full image)
5. Device reconstructs v2 from v1 + diff
6. Device installs reconstructed v2.swu

**Layer artifacts used**: 
- `adu-delta-v1-to-v2.diff` (tiny binary patch)
- `adu-update-image-v1.swu` (required on device as source)

**Benefits**: 
- **99.9% bandwidth savings** (600 bytes vs 240MB)
- Faster OTA deployment
- Lower cloud egress costs

---

### Demo 3: Multi-Step Update Chain
**Requirements**: ADU agent + persistence layer
**What it demonstrates**: Controlled rollout through multiple versions

**Steps**:
1. Device at v1 (1.0.0)
2. Update to v2 (1.0.1) - verify functionality
3. Update to v3 (1.0.2) - demonstrate continuous updates
4. Optional: Rollback from v3 → v2 → v1

**Layer artifacts used**:
- `adu-update-image-v1.swu`
- `adu-update-image-v2.swu`
- `adu-update-image-v3.swu`
- `adu-delta-v1-to-v2.diff`
- `adu-delta-v2-to-v3.diff`

---

### Demo 4: Import Manifest Generation
**Requirements**: Azure Device Update account
**What it demonstrates**: Automated manifest creation for Azure Portal

**Steps**:
1. Build images and manifests: `bitbake adu-import-manifests`
2. Navigate to `tmp/deploy/images/<MACHINE>/import-manifests/`
3. Find JSON manifests for each update package
4. Import to Azure Portal via UI or CLI
5. Deploy to device groups

**Layer artifacts used**: `adu-import-manifests-v*.json`

---

## Feature Requirements Matrix

| Feature | Layer Components | BSP Requirements | External Requirements |
|---------|-----------------|------------------|----------------------|
| **Full Image Updates** | adu-update-image-v*.bb | virtual/adu-base-image provider, Boot A/B slots | Azure IoT Hub, ADU account |
| **Delta Updates** | adu-delta-image.bb | Same as above + storage for temp files | ADU with delta support enabled |
| **Manifest Generation** | adu-import-manifests.bb | Image metadata (version, size) | None (offline operation) |
| **Version Testing** | All three versioned images | Persistence across updates | Test IoT Hub instance |
| **Rollback Demo** | v1, v2, v3 images | A/B boot slots with fallback | ADU agent with rollback support |

---

## Customizing for Your Platform

### Step 1: Create Your Base Image Recipe

In your BSP layer (e.g., `meta-myboard/recipes-core/images/myboard-base.bb`):

```bitbake
require recipes-core/images/core-image-minimal.bb

DESCRIPTION = "My board base image for ADU"

# Essential: Provide virtual interface
PROVIDES = "virtual/adu-base-image"

# Add your board-specific packages
IMAGE_INSTALL:append = " \
    myboard-firmware \
    myboard-drivers \
"

# Configure persistence, boot, etc.
inherit adu-filesystem-layout
```

### Step 2: Configure local.conf

```bitbake
# Point to your base image
PREFERRED_PROVIDER_virtual/adu-base-image = "myboard-base"

# Set your company/model info
ADU_PROVIDER = "MyCompany"
ADU_MODEL = "MyDevice-v1"

# Configure signing keys
ADUC_PRIVATE_KEY = "${TOPDIR}/../keys/priv.pem"
ADUC_PRIVATE_KEY_PASSWORD = "${TOPDIR}/../keys/priv.pass"
```

### Step 3: Build Samples

```bash
bitbake adu-update-image-v1
```

The sample images will automatically wrap your board's base image in SWUpdate format.

---

## Layer Configuration

### Default Variables (can be overridden)

Defined in `conf/distro/include/adu-samples-defaults.inc`:

```bitbake
# Software version for all samples
BASE_ADU_SOFTWARE_VERSION ?= "1.0.0"

# ADU manifest metadata
ADU_PROVIDER ?= "Contoso"
ADU_MODEL ?= "Video"
```

### Layer Dependencies

Defined in `conf/layer.conf`:

```bitbake
LAYERDEPENDS_meta-azure-device-update-samples = " \
    core \
    azure-device-update \
    iot-hub-device-update-delta \
    swupdate \
"
```

### Recipe Naming Convention

- **Samples**: `recipes-samples/images/adu-update-image-v*.bb`
- **Delta**: `recipes-samples/delta-generation/adu-delta-image.bb`
- **Manifests**: `recipes-samples/delta-generation/adu-import-manifests.bb`

---

## Troubleshooting

### Issue: "Nothing PROVIDES virtual/adu-base-image"

**Cause**: Your BSP layer doesn't provide the required virtual interface.

**Solution**: Add to your base image recipe:
```bitbake
PROVIDES = "virtual/adu-base-image"
```

And set in `local.conf`:
```bitbake
PREFERRED_PROVIDER_virtual/adu-base-image = "your-base-image-name"
```

---

### Issue: "Delta files are large (not ~600 bytes)"

**Cause**: Base image contains changing files (logs, timestamps, random data).

**Solution**: 
- Ensure base image uses reproducible builds
- Remove volatile data from rootfs
- Use timestamp masking in your image recipe

---

### Issue: "BBFILE_COLLECTIONS version mismatch"

**Cause**: `POKY_BBLAYERS_CONF_VERSION` in `bblayers.conf` doesn't match template.

**Solution**: Update `conf/bblayers.conf`:
```bitbake
POKY_BBLAYERS_CONF_VERSION = "3"  # Match template version
```

---

## Contributing

This layer is maintained as part of the Azure Device Update for IoT Hub project.

**Contribution areas**:
- Additional demo scenarios
- Platform-specific examples (in comments/docs, not code)
- Improved delta generation strategies
- Test automation

---

## License

MIT License - See [LICENSE](LICENSE) file

---

## Related Documentation

- [Azure Device Update Documentation](https://docs.microsoft.com/azure/iot-hub-device-update/)
- [SWUpdate Documentation](https://sbabic.github.io/swupdate/)
- [Yocto Project Mega-Manual](https://docs.yoctoproject.org/)
- [iot-hub-device-update-yocto Main README](../../README.md)

---

## Support

For issues related to:
- **This layer**: File an issue in the iot-hub-device-update-yocto repository
- **ADU Agent**: See [iot-hub-device-update](https://github.com/Azure/iot-hub-device-update)
- **Delta Library**: See [iot-hub-device-update-delta](https://github.com/Azure/iot-hub-device-update-delta)
- **Yocto/BitBake**: Consult [Yocto Project](https://www.yoctoproject.org/)

## Documentation

See `docs/` directory:
- `QUICKSTART.md` - Detailed setup guide
- `DELTA-TESTING.md` - Delta testing workflows
- `CONTRIBUTING.md` - Contribution guidelines

## License

MIT License - See LICENSE file

## Maintainers

- Azure Device Update Team

## Support

For issues and questions:
- File issues in the repository
- See `docs/` for detailed documentation
