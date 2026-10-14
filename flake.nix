{
  description = "ticker dev shell";
  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  outputs = { self, nixpkgs }: let
    system = "x86_64-linux";
  in {
    devShells.${system}.default = nixpkgs.legacyPackages.${system}.mkShell {
      packages = [ nixpkgs.legacyPackages.${system}.jdk21 ];
      # iOS builds happen on macOS CI only (xcbuild / Xcode required).
      # Android SDK is not needed locally; ./gradlew :shared:jvmTest runs under JDK 21 only.
    };
  };
}
