#!/usr/bin/env python3
"""Patch Flutter-generated ios/ for CricRelay Stream (HaishinKit + native RTMP plugin)."""
from __future__ import annotations

import re
import sys
from pathlib import Path


def patch_podfile(ios: Path) -> None:
    podfile = ios / "Podfile"
    text = podfile.read_text(encoding="utf-8")
    if "HaishinKit" not in text:
        text = text.replace(
            "flutter_install_all_ios_pods File.dirname(File.realpath(__FILE__))",
            "flutter_install_all_ios_pods File.dirname(File.realpath(__FILE__))\n  pod 'HaishinKit', '~> 2.0.9'",
        )
    if "platform :ios" in text:
        text = re.sub(r"platform :ios, '\d+\.\d+'", "platform :ios, '15.0'", text)
    podfile.write_text(text, encoding="utf-8")
    print("patched", podfile)


def patch_app_delegate(ios: Path) -> None:
    delegate = ios / "Runner" / "AppDelegate.swift"
    text = delegate.read_text(encoding="utf-8")
    if "StreamRtmpPlugin" in text:
        return
    needle = "GeneratedPluginRegistrant.register(with: self)"
    insert = """    if #available(iOS 15.0, *) {
      StreamRtmpPlugin.register(with: self.registrar(forPlugin: "StreamRtmpPlugin")!)
    }
    """
    if needle not in text:
        raise SystemExit(f"could not patch {delegate}")
    text = text.replace(needle, insert + "    " + needle)
    delegate.write_text(text, encoding="utf-8")
    print("patched", delegate)


def patch_xcode_project(ios: Path, swift_files: list[Path]) -> None:
    pbx = ios / "Runner.xcodeproj" / "project.pbxproj"
    text = pbx.read_text(encoding="utf-8")
    for swift in swift_files:
        name = swift.name
        if name in text:
            continue
        file_ref = f"CR{name.upper().replace('.', '_')}_REF"
        build_ref = f"CR{name.upper().replace('.', '_')}_BUILD"
        text = text.replace(
            "/* End PBXBuildFile section */",
            f"\t\t{build_ref} /* {name} in Sources */ = {{isa = PBXBuildFile; fileRef = {file_ref} /* {name} */; }};\n"
            "/* End PBXBuildFile section */",
        )
        text = text.replace(
            "/* End PBXFileReference section */",
            f"\t\t{file_ref} /* {name} */ = {{isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = {name}; sourceTree = \"<group>\"; }};\n"
            "/* End PBXFileReference section */",
        )
        text = text.replace(
            "AppDelegate.swift,\n",
            f"AppDelegate.swift,\n\t\t\t\t{file_ref} /* {name} */,\n",
        )
        text = text.replace(
            "AppDelegate.swift in Sources */,\n",
            f"AppDelegate.swift in Sources */,\n\t\t\t\t{build_ref} /* {name} in Sources */,\n",
        )
    pbx.write_text(text, encoding="utf-8")
    print("patched", pbx)


def patch_info_plist(ios: Path) -> None:
    plist = ios / "Runner" / "Info.plist"
    text = plist.read_text(encoding="utf-8")
    if "UIBackgroundModes" not in text:
        text = text.replace(
            "</dict>\n</plist>",
            "\t<key>UIBackgroundModes</key>\n\t<array>\n\t\t<string>audio</string>\n\t</array>\n</dict>\n</plist>",
        )
    plist.write_text(text, encoding="utf-8")
    print("patched", plist)


def main() -> int:
    root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    ios = root / "ios"
    custom = root / "ios-custom"
    if not ios.is_dir():
        print(f"missing {ios}", file=sys.stderr)
        return 1

    dest = ios / "Runner"
    swift_files: list[Path] = []
    for src in sorted((custom / "StreamCamera").glob("*.swift")):
        target = dest / src.name
        target.write_text(src.read_text(encoding="utf-8"), encoding="utf-8")
        swift_files.append(target)
        print("copied", target)

    patch_podfile(ios)
    patch_app_delegate(ios)
    patch_xcode_project(ios, swift_files)
    patch_info_plist(ios)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
