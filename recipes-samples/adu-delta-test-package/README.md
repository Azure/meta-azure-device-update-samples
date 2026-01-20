# ADU Delta Test Package Recipe

This recipe automatically generates a comprehensive delta update test package as part of the Yocto build.

## What It Does

Creates a tarball containing:
- All ADU images (base, v1, v2, v3, recompressed versions)
- Pre-generated delta files
- Delta manifests
- Testing tools (diffgentool, dumpextfs if available)
- Cache management scripts
- Testing documentation

## Building

The package is built automatically after images are complete:

```bash
bitbake adu-delta-test-package
```

Or as part of the complete build:

```bash
bitbake adu-delta-image
```

## Output

**Location**: `tmp/deploy/images/raspberrypi4-64/adu-delta-test-package.tar.gz`

**Contents**:
- `images/` - All image files and deltas
- `tools/` - Delta processing tools
- `scripts/` - cache_manager.sh, delta_operations.py
- `docs/` - Testing guides
- `README.md` - Quick start guide

## Usage on Device

```bash
# Transfer to Raspberry Pi
scp tmp/deploy/images/raspberrypi4-64/adu-delta-test-package.tar.gz pi@device:~

# Extract and use
tar -xzf adu-delta-test-package.tar.gz
cd delta-test-package-*/
./scripts/cache_manager.sh store images/adu-update-image-v1-recompressed.swu "Contoso" "1.0.0"
```

## Integration

Add to your local.conf or image recipe:

```
# Automatically build test package with images
IMAGE_INSTALL:append = " adu-delta-test-package"
```

Or build separately:

```bash
bitbake adu-delta-test-package
```

## Dependencies

Depends on:
- adu-base-image
- adu-update-image-v1
- adu-update-image-v2
- adu-update-image-v3
- adu-delta-image (for delta file generation)

The recipe runs after all images are built.

## Customization

To modify the package contents, edit:
- `adu-delta-test-package.bb` - Main recipe logic
- Scripts are embedded in the recipe's do_install task

## Notes

- Package name includes timestamp: `delta-test-package-YYYYMMDD-HHMMSS`
- Symlink `adu-delta-test-package.tar.gz` always points to latest
- Recipe doesn't create binary packages (nopackage inherit)
- Uses EXCLUDE_FROM_WORLD to prevent automatic builds
