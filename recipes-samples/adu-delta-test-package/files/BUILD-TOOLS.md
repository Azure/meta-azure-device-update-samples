# Build-Time Delta Tools Reference

This document describes the native (x86_64) tools used during the Yocto build process to generate delta files and recompressed SWUpdate archives. These tools are **NOT included** in the delta test package because they:

1. Are compiled for x86_64 (build host architecture)
2. Run during bitbake builds, not on target devices
3. Are already available in the Yocto build environment

## Recipe Architecture

The delta tools are split across multiple recipes in `meta-iot-hub-device-update-delta`:

| Recipe | Purpose | Output |
|--------|---------|--------|
| `iot-hub-device-update-delta-diffgentool-native` | .NET 8 delta generation tool | `DiffGenTool` |
| `iot-hub-device-update-delta-processor-native` | Native C++ tools for build host | `applydiff`, `recompress`, etc. |
| `iot-hub-device-update-delta-processor` | Target ARM64 library | `libadudiffapi.so` |

## Tools Overview

### DiffGenTool (.NET Application)

**Purpose**: Generate binary delta files between source and target SWU images.

**Location**: Built by `iot-hub-device-update-delta-diffgentool-native` recipe  
**Architecture**: x86_64 (native) — .NET 8 application  
**Build Recipe**: `meta-iot-hub-device-update-delta/recipes-azure/iot-hub-device-update-delta/iot-hub-device-update-delta-diffgentool-native_3.0.bb`

**Usage**:
```bash
DiffGenTool \
    --source-file <source.swu> \
    --target-file <target.swu> \
    --output-file <output.diff> \
    --log-folder <log-dir>
```

**Used By**: `adu-delta-image.bb` recipe to generate delta files between update versions.

**Common Options**:
- `--source-file`: Path to source (old) SWU file
- `--target-file`: Path to target (new) SWU file
- `--output-file`: Path for generated delta file (.diff)
- `--log-folder`: Directory for diagnostic logs
- `--recompress-tool-path`: Path to recompress tool (optional)

> **Note**: DiffGenTool is a .NET 8 application. The build host requires .NET SDK 8.0 installed (see `install-deps.sh`).

### applydiff

**Purpose**: Apply a delta file to a source to reconstruct the target.

**Location**: Built by `iot-hub-device-update-delta-processor-native` recipe  
**Architecture**: x86_64 (native C++)  
**Build Recipe**: `meta-iot-hub-device-update-delta/recipes-azure/iot-hub-device-update-delta/iot-hub-device-update-delta-processor-native_3.0.bb`

**Usage**:
```bash
applydiff <source.swu> <delta.diff> <output.swu>
```

**Used By**: Build verification tasks to ensure delta reconstruction works correctly.

### recompress

**Purpose**: Recompress SWUpdate archives to optimize for delta generation.

**Location**: Built by `iot-hub-device-update-delta-processor-native` recipe  
**Architecture**: x86_64 (native C++)  
**Build Recipe**: Same as applydiff

**Usage**:
```bash
recompress <input.swu> <output.swu>
```

**Used By**: `adu-delta-image.bb` to recompress update files before delta generation.

**Why Recompress?**:
- Ensures deterministic compression for reliable delta generation
- Removes compression-related entropy that would create larger deltas
- Standardizes compression parameters across all update files

### Additional Native Tools

Built by `iot-hub-device-update-delta-processor-native`:

| Tool | Purpose |
|------|---------|
| `dumpdiff` | Inspect and debug delta file contents |
| `dumpextfs` | Dump ext4 filesystem metadata for analysis |
| `extract` | Extract contents from CPIO/SWU archives |
| `makecpio` | Create CPIO archives |
| `zstd_compress_file` | Zstandard compression utility |

### libadudiffapi.so

**Purpose**: Shared library providing delta generation/application APIs.

**Location**: Built by delta-processor recipes  
**Architecture**: x86_64 (native) and ARM64 (target)  
**Build Recipes**: 
- Native: `iot-hub-device-update-delta-processor-native` (for DiffGenTool)
- Target: `iot-hub-device-update-delta-processor` (for on-device delta handler)

**Used By**: 
- Native: DiffGenTool and applydiff link against native version
- Target: ADU delta download handler on device uses ARM64 version

**Key APIs**:
- Delta generation interface
- Delta application interface
- File chunk management
- Compression handling (zstd, zlib)

## Build Workflow

The typical build workflow uses these tools in sequence:

```
1. Build base image (adu-base-image.bb)
   └─> adu-base-image.wic.gz

2. Build update images (adu-update-image-v1/v2/v3.bb)
   └─> adu-update-image-v1.swu
   └─> adu-update-image-v2.swu
   └─> adu-update-image-v3.swu

3. Recompress updates (adu-delta-image.bb)
   └─> Uses: recompress (native C++)
   └─> Generates: *-recompressed.swu files

4. Generate deltas (adu-delta-image.bb)
   └─> Uses: DiffGenTool (.NET 8)
   └─> Generates:
       • delta-v1-to-v2.diff
       • delta-v2-to-v3.diff
       • delta-v1-to-v3.diff

5. Create import manifests (adu-delta-image.bb)
   └─> Uses: generate_import_manifest.py
   └─> Generates: import-manifest-*.json

6. Package for deployment (adu-delta-test-package.bb)
   └─> Combines: SWU files + deltas + manifests
   └─> Generates: adu-delta-test-package.tar.gz
```

## Finding Tools in Build Environment

During bitbake builds, these tools are available in:

```bash
# DiffGenTool (.NET 8)
${TMPDIR}/work/x86_64-linux/iot-hub-device-update-delta-diffgentool-native/3.0/git/src/out/managed/Release/DiffGenTool/net8.0/linux-x64/DiffGenTool

# Native C++ tools (applydiff, recompress, etc.)
${TMPDIR}/work/x86_64-linux/iot-hub-device-update-delta-processor-native/3.0/build/bin/

# Target library (ARM64)
${TMPDIR}/work/cortexa72-poky-linux/iot-hub-device-update-delta-processor/3.0/build/bin/

# Example paths:
tmp/work/x86_64-linux/iot-hub-device-update-delta-processor-native/3.0/build/bin/applydiff
tmp/work/x86_64-linux/iot-hub-device-update-delta-processor-native/3.0/build/bin/recompress
tmp/work/x86_64-linux/iot-hub-device-update-delta-processor-native/3.0/build/bin/dumpextfs
```

## Target vs Build Tools

| Tool | Native (Build Host) | Target (Device) | Purpose |
|------|---------------------|-----------------|---------|
| DiffGenTool | ✅ x86_64 (.NET 8) | ❌ Not needed | Generate deltas during build |
| applydiff | ✅ x86_64 (C++) | ❌ Not needed | Verify deltas during build |
| recompress | ✅ x86_64 (C++) | ❌ Not needed | Prepare SWU for delta generation |
| dumpdiff | ✅ x86_64 (C++) | ❌ Not needed | Debug delta file contents |
| dumpextfs | ✅ x86_64 (C++) | ❌ Not needed | Debug ext4 filesystem metadata |
| extract | ✅ x86_64 (C++) | ❌ Not needed | Extract CPIO/SWU archives |
| makecpio | ✅ x86_64 (C++) | ❌ Not needed | Create CPIO archives |
| zstd_compress_file | ✅ x86_64 (C++) | ❌ Not needed | Zstd compression |
| libadudiffapi.so | ✅ x86_64 | ✅ ARM64 | Native: DiffGenTool; Target: delta handler |

## On-Device Delta Processing

The device uses a different mechanism for delta processing:

1. **ADU Agent** receives delta update via Azure IoT Hub
2. **Delta Download Handler** (`libadudelta.so`) is invoked by extension manager
3. Handler uses **target ARM64 libadudiffapi.so** to apply delta
4. Source is retrieved from **Standalone Delta Cache** (`/var/lib/adu/sdc/`)
5. Reconstructed target is saved to download directory
6. **SWUpdate Handler** installs the reconstructed update

The device does NOT need DiffGenTool, applydiff, recompress, or other build tools because delta application is handled by the delta download handler library (`libadudiffapi.so`).

## Verifying Tool Availability

To verify native tools are available during build:

```bash
# In bitbake devshell or recipe:
which applydiff
which recompress
which dumpextfs

# Check DiffGenTool (.NET):
ls ${STAGING_BINDIR_NATIVE}/DiffGenTool

# Check library availability:
ls ${STAGING_LIBDIR_NATIVE}/libadudiffapi.so

# Test tool execution:
applydiff --help
recompress --help
DiffGenTool --help
```

## Troubleshooting Build Issues

### "DiffGenTool: command not found"

**Cause**: iot-hub-device-update-delta-diffgentool-native not built or .NET SDK missing

**Solution**:
```bash
# Ensure .NET SDK 8.0 is installed on build host
dotnet --version

# Build the DiffGenTool recipe
bitbake iot-hub-device-update-delta-diffgentool-native

# Or add to recipe:
DEPENDS += "iot-hub-device-update-delta-diffgentool-native"
```

### "applydiff/recompress: command not found"

**Cause**: iot-hub-device-update-delta-processor-native not built or not in PATH

**Solution**:
```bash
bitbake iot-hub-device-update-delta-processor-native
# Or add to recipe:
DEPENDS += "iot-hub-device-update-delta-processor-native"
```

### "libadudiffapi.so: cannot open shared object"

**Cause**: Library not in LD_LIBRARY_PATH during build

**Solution**:
```bash
# In recipe:
DEPENDS += "iot-hub-device-update-delta-processor-native"
# Library will be automatically found via sysroot
```

### Delta generation fails with large files

**Cause**: Insufficient memory or disk space

**Solution**:
- Increase BB_DISKMON_DIRS thresholds in local.conf
- Add more swap space to build machine (16GB+ recommended)
- Use compression to reduce memory footprint

### .NET SDK not found during build

**Cause**: .NET SDK 8.0 not installed on build host

**Solution**:
```bash
# Run install-deps.sh which includes .NET SDK installation
./scripts/install-deps.sh

# Or install manually:
wget https://dot.net/v1/dotnet-install.sh -O /tmp/dotnet-install.sh
chmod +x /tmp/dotnet-install.sh
sudo /tmp/dotnet-install.sh --channel 8.0 --install-dir /usr/share/dotnet
sudo ln -s /usr/share/dotnet/dotnet /usr/bin/dotnet
```

## References

- **Delta Generation**: [iot-hub-device-update-delta README](https://github.com/Azure/iot-hub-device-update-delta/blob/main/README.md)
- **ADU Delta Handler**: [iot-hub-device-update delta handler docs](https://github.com/Azure/iot-hub-device-update/tree/main/src/extensions/download_handlers/delta_download_handler)
- **Yocto Native Recipes**: [Yocto Manual - Native Recipes](https://docs.yoctoproject.org/ref-manual/classes.html#native-bbclass)
- **.NET SDK Install**: [Microsoft .NET Install Guide](https://learn.microsoft.com/dotnet/core/install/linux)
