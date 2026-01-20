#!/usr/bin/env python3
"""
Generate Azure Device Update import manifests for delta testing
Conforms to ADU Import Manifest v5.0 schema
"""

import json
import hashlib
import base64
import os
from datetime import datetime
from collections import OrderedDict

def calculate_sha256_base64(filepath):
    """Calculate SHA256 hash and return as base64 string"""
    sha256_hash = hashlib.sha256()
    with open(filepath, "rb") as f:
        for byte_block in iter(lambda: f.read(4096), b""):
            sha256_hash.update(byte_block)
    return base64.b64encode(sha256_hash.digest()).decode('utf-8')

def get_file_size(filepath):
    """Get file size in bytes"""
    return os.path.getsize(filepath)

def generate_import_manifest(version, swu_file, swu_hash, swu_size, script_file, script_hash, script_size,
                            installed_criteria, related_files=None, enable_delta_caching=False,
                            provider="contoso", name="adu-yocto-rpi4-poc-1", 
                            manufacturer="contoso", model="adu-yocto-rpi4-poc-1"):
    """
    Generate an import manifest conforming to ADU v5.0 schema
    
    Args:
        version: Update version (e.g., "1.0.1")
        swu_file: Name of the SWU file
        swu_hash: Base64 SHA256 hash of SWU file
        swu_size: Size of SWU file in bytes
        script_file: Name of the update script
        script_hash: Base64 SHA256 hash of script
        script_size: Size of script in bytes
        installed_criteria: Installed criteria string
        related_files: List of dicts with delta file info [{"filename": "...", "hash": "...", "size": ..., "sourceHash": "..."}]
    """
    
    manifest = OrderedDict([
        ("$schema", "https://json.schemastore.org/azure-deviceupdate-import-manifest-5.0.json"),
        ("manifestVersion", "5.0"),
        ("updateId", OrderedDict([
            ("provider", provider),
            ("name", name),
            ("version", version)
        ])),
        ("compatibility", [
            OrderedDict([
                ("deviceManufacturer", manufacturer),
                ("deviceModel", model)
            ])
        ]),
        ("instructions", OrderedDict([
            ("steps", [
                OrderedDict([
                    ("handler", "microsoft/swupdate:2"),
                    ("files", [swu_file, script_file]),
                    ("handlerProperties", OrderedDict([
                        ("installedCriteria", installed_criteria),
                        ("swuFileName", swu_file),
                        ("scriptFileName", script_file),
                        ("apiVersion", "1.1"),
                        ("arguments", f"--software-version-file /etc/adu-version --swupdate-log-file /var/log/adu/swupdate.log --restart-to-apply --custom-workflow-id dev-update-workflow-{version} --log-level 0")
                    ]))
                ])
            ])
        ])),
        ("files", [
            OrderedDict([
                ("filename", swu_file),
                ("sizeInBytes", swu_size),
                ("hashes", OrderedDict([
                    ("sha256", swu_hash)
                ]))
            ]),
            OrderedDict([
                ("filename", script_file),
                ("sizeInBytes", script_size),
                ("hashes", OrderedDict([
                    ("sha256", script_hash)
                ]))
            ])
        ]),
        ("createdDateTime", datetime.utcnow().strftime("%Y-%m-%dT%H:%M:%SZ"))
    ])
    
    # Add related files (deltas) if present
    if related_files and len(related_files) > 0:
        related_files_array = []
        for rf in related_files:
            related_files_array.append(OrderedDict([
                ("filename", rf["filename"]),
                ("sizeInBytes", rf["size"]),
                ("hashes", OrderedDict([
                    ("sha256", rf["hash"])
                ])),
                ("properties", OrderedDict([
                    ("microsoft.sourceFileHashAlgorithm", "sha256"),
                    ("microsoft.sourceFileHash", rf["sourceHash"])
                ]))
            ]))
        
        # Add relatedFiles and downloadHandler to the SWU file entry
        manifest["files"][0]["relatedFiles"] = related_files_array
        manifest["files"][0]["downloadHandler"] = OrderedDict([
            ("id", "microsoft/delta:1")
        ])
        
    # Add deltaHandler if delta caching is enabled (for v1.0.1 base update)
    # This tells the agent to save the .swu file to source delta cache for future delta operations
    if enable_delta_caching:
        manifest["files"][0]["deltaHandler"] = OrderedDict([
            ("id", "microsoft/delta:1")
        ])
    
    return manifest

def main():
    import sys
    import argparse
    
    parser = argparse.ArgumentParser(description='Generate ADU import manifests for delta testing')
    parser.add_argument('--images-dir', required=True, help='Directory containing image files')
    parser.add_argument('--output-dir', required=True, help='Output directory for manifests')
    parser.add_argument('--script-file', default='yocto-a-b-update.sh', help='Update script filename')
    parser.add_argument('--provider', default='contoso', help='Update provider')
    parser.add_argument('--name', default='adu-yocto-rpi4-poc-1', help='Update name')
    parser.add_argument('--manufacturer', default='contoso', help='Device manufacturer')
    parser.add_argument('--model', default='adu-yocto-rpi4-poc-1', help='Device model')
    
    args = parser.parse_args()
    
    images_dir = args.images_dir
    output_dir = args.output_dir
    script_file = args.script_file
    
    # Ensure output directory exists
    os.makedirs(output_dir, exist_ok=True)
    
    # Find and hash script file (look in images dir for now)
    script_path = os.path.join(images_dir, script_file)
    if not os.path.exists(script_path):
        print(f"ERROR: Script file not found: {script_path}", file=sys.stderr)
        sys.exit(1)
    
    script_hash = calculate_sha256_base64(script_path)
    script_size = get_file_size(script_path)
    
    # Calculate hashes for all SWU files
    swu_files = {
        'v1': 'adu-update-image-v1.0.0-recompressed.swu',
        'v2': 'adu-update-image-v2.0.0-recompressed.swu',
        'v3': 'adu-update-image-v3.0.0-recompressed.swu'
    }
    
    swu_hashes = {}
    swu_sizes = {}
    
    print("Calculating SWU file hashes...")
    for version, filename in swu_files.items():
        filepath = os.path.join(images_dir, filename)
        if os.path.exists(filepath):
            swu_hashes[version] = calculate_sha256_base64(filepath)
            swu_sizes[version] = get_file_size(filepath)
            print(f"  {version}: {filename} -> {swu_hashes[version][:16]}...")
        else:
            print(f"WARNING: SWU file not found: {filepath}", file=sys.stderr)
    
    # Calculate hashes for delta files
    delta_files = {
        'v1-to-v2': 'adu-delta-v1-to-v2.diff',
        'v1-to-v3': 'adu-delta-v1-to-v3.diff',
        'v2-to-v3': 'adu-delta-v2-to-v3.diff'
    }
    
    delta_hashes = {}
    delta_sizes = {}
    
    print("\nCalculating delta file hashes...")
    for version, filename in delta_files.items():
        filepath = os.path.join(images_dir, filename)
        if os.path.exists(filepath):
            delta_hashes[version] = calculate_sha256_base64(filepath)
            delta_sizes[version] = get_file_size(filepath)
            print(f"  {version}: {filename} -> {delta_hashes[version][:16]}...")
        else:
            print(f"WARNING: Delta file not found: {filepath}", file=sys.stderr)
    
    # Generate manifest for v1.0.0 (base, no deltas, but enable delta caching)
    print("\nGenerating import manifest for v1.0.0 (base update with delta caching)...")
    if 'v1' in swu_hashes:
        manifest_v1 = generate_import_manifest(
            version="1.0.0",
            swu_file=swu_files['v1'],
            swu_hash=swu_hashes['v1'],
            swu_size=swu_sizes['v1'],
            script_file=script_file,
            script_hash=script_hash,
            script_size=script_size,
            installed_criteria="1.0.0.1",
            enable_delta_caching=True,  # Enable caching for future delta updates
            provider=args.provider,
            name=args.name,
            manufacturer=args.manufacturer,
            model=args.model
        )
        
        manifest_path = os.path.join(output_dir, f"{args.provider}.{args.name}.1.0.0.importmanifest.json")
        with open(manifest_path, 'w') as f:
            json.dump(manifest_v1, f, indent=2)
        print(f"  Created: {manifest_path}")
        print(f"  Note: deltaHandler enabled - .swu will be cached to SDC folder after successful install")
    
    # Generate manifest for v2.0.0 (delta from v1)
    print("\nGenerating import manifest for v2.0.0 (delta from v1)...")
    if 'v2' in swu_hashes and 'v1-to-v2' in delta_hashes:
        related_files_v2 = [
            {
                "filename": delta_files['v1-to-v2'],
                "hash": delta_hashes['v1-to-v2'],
                "size": delta_sizes['v1-to-v2'],
                "sourceHash": swu_hashes['v1']
            }
        ]
        
        manifest_v2 = generate_import_manifest(
            version="2.0.0",
            swu_file=swu_files['v2'],
            swu_hash=swu_hashes['v2'],
            swu_size=swu_sizes['v2'],
            script_file=script_file,
            script_hash=script_hash,
            script_size=script_size,
            installed_criteria="1.0.0.2",
            related_files=related_files_v2,
            provider=args.provider,
            name=args.name,
            manufacturer=args.manufacturer,
            model=args.model
        )
        
        manifest_path = os.path.join(output_dir, f"{args.provider}.{args.name}.2.0.0.importmanifest.json")
        with open(manifest_path, 'w') as f:
            json.dump(manifest_v2, f, indent=2)
        print(f"  Created: {manifest_path}")
    
    # Generate manifest for v3.0.0 (deltas from v1 and v2)
    print("\nGenerating import manifest for v3.0.0 (deltas from v1 and v2)...")
    if 'v3' in swu_hashes and 'v1-to-v3' in delta_hashes and 'v2-to-v3' in delta_hashes:
        related_files_v3 = [
            {
                "filename": delta_files['v1-to-v3'],
                "hash": delta_hashes['v1-to-v3'],
                "size": delta_sizes['v1-to-v3'],
                "sourceHash": swu_hashes['v1']
            },
            {
                "filename": delta_files['v2-to-v3'],
                "hash": delta_hashes['v2-to-v3'],
                "size": delta_sizes['v2-to-v3'],
                "sourceHash": swu_hashes['v2']
            }
        ]
        
        manifest_v3 = generate_import_manifest(
            version="3.0.0",
            swu_file=swu_files['v3'],
            swu_hash=swu_hashes['v3'],
            swu_size=swu_sizes['v3'],
            script_file=script_file,
            script_hash=script_hash,
            script_size=script_size,
            installed_criteria="1.0.0.3",
            related_files=related_files_v3,
            provider=args.provider,
            name=args.name,
            manufacturer=args.manufacturer,
            model=args.model
        )
        
        manifest_path = os.path.join(output_dir, f"{args.provider}.{args.name}.3.0.0.importmanifest.json")
        with open(manifest_path, 'w') as f:
            json.dump(manifest_v3, f, indent=2)
        print(f"  Created: {manifest_path}")
    
    print("\n✓ Import manifest generation complete!")

if __name__ == "__main__":
    main()
