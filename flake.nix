{
  description = "LogOut full development system & tooling";
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    agents-jail.url = "github:gfauredev/nix-agents-jail";
  };
  outputs =
    {
      self,
      nixpkgs,
      agents-jail,
    }:
    let
      forAllSystems = nixpkgs.lib.genAttrs [
        "x86_64-linux"
        "aarch64-darwin"
      ];
      nixpkgsFor = forAllSystems (
        system:
        import nixpkgs {
          inherit system;
          config = {
            allowUnfree = true;
            android_sdk.accept_license = true;
          };
        }
      );
      sharedEnvFor =
        system:
        let
          pkgs = nixpkgsFor.${system};
          assetFilter =
            path: type: builtins.match ".*(/public/.*|/assets/.*|/icon/.*|index\\.html|.*\\.png)$" path != null;
          sourceFilter = path: type: (assetFilter path type);
          filteredSrc = pkgs.lib.cleanSourceWith {
            src = ./.;
            filter = sourceFilter;
          };
          commonNativeBuildInputs = with pkgs; [
            binaryen
            clang
            patchelf
            pkg-config
            unzip
          ];
          commonBuildInputs = [
            pkgs.openssl
          ]
          ++ pkgs.lib.optionals pkgs.stdenv.hostPlatform.isDarwin [
            pkgs.darwin.apple_sdk.frameworks.Security
            pkgs.darwin.apple_sdk.frameworks.SystemConfiguration
          ];
          chromiumWrapper = pkgs.writeShellScriptBin "google-chrome" ''
            exec "${pkgs.ungoogled-chromium}/bin/chromium" --no-sandbox "$@"
          '';
          SE_CHROME_PATH = "${chromiumWrapper}/bin/google-chrome";
          androidComposition = pkgs.androidenv.composeAndroidPackages {
            platformVersions = [
              "35"
              "36"
            ];
            buildToolsVersions = [ "34.0.0" ];
            includeNDK = true;
            includeEmulator = false;
            includeSystemImages = false;
            abiVersions = [
              "arm64-v8a"
              "x86_64"
            ];
          };
          androidNativeBuildInputs = with pkgs; [
            aapt
            apksigner
            android-tools
            androidComposition.androidsdk
            androidComposition.ndk-bundle
            cargo-ndk
            openjdk
          ];
        in
        {
          projectVersion = "0.1.0";
          projectName = "ProtonSync";
          projectSlug = "proton-sync";
          inherit
            pkgs
            filteredSrc
            androidComposition
            commonNativeBuildInputs
            androidNativeBuildInputs
            commonBuildInputs
            SE_CHROME_PATH
            ;
        };
    in
    {
      packages = forAllSystems (
        system:
        let
          env = sharedEnvFor system;
          mkAndroidBuilder =
            {
              target ? "aarch64-linux-android",
            }:
            env.pkgs.writeShellApplication {
              name = "${env.projectSlug}-android-${env.projectVersion}";
              runtimeInputs = env.commonNativeBuildInputs ++ env.androidNativeBuildInputs;
              text = ''
                unset ANDROID_SDK_ROOT # Conflicts with Home in GitHub Runners
                export ANDROID_HOME="${env.androidComposition.androidsdk}/libexec/android-sdk"
                export ANDROID_NDK_HOME="${env.androidComposition.ndk-bundle}/libexec/android-sdk/ndk-bundle"
                export GRADLE_USER_HOME="''${GRADLE_USER_HOME:-$PWD/.gradle}" 
                export HOME="''${HOME:-$TMPDIR}"
                export GRADLE_OPTS="''${GRADLE_OPTS:-} -Dorg.gradle.project.android.aapt2FromMavenOverride=$ANDROID_HOME/build-tools/34.0.0/aapt2"
                echo "🤖 ${env.projectName} Build Environment Ready"
                echo "- Android SDK $ANDROID_HOME"
                echo "- Android NDK $ANDROID_NDK_HOME"
              '';
            };
        in
        {
          androidBuild = mkAndroidBuilder { };
          androidE2eTest = env.pkgs.writeShellApplication {
            name = "${env.projectSlug}-android-e2e-${env.projectVersion}";
            runtimeInputs = [ env.pkgs.maestro ];
            # TODO Android emulator…
            text = ''
              maestro test --headless "${self}/maestro/android"
            '';
          };
          default = env.pkgs.symlinkJoin {
            name = "${env.projectSlug}-all-${env.projectVersion}";
            paths = [
              self.packages.${system}.androidBuild
              self.packages.${system}.androidE2eTest
            ];
          };
        }
      );
      # apps = forAllSystems (system: {
      #   default = {
      #     type = "app";
      #     program = "${self.packages.${system}.androidE2eTest}";
      #     meta.description = "Test app in emulator";
      #   };
      # });
      devShells = forAllSystems (
        system:
        let
          env = sharedEnvFor system;
          devTools = with env.pkgs; [
            cachix # Nix binary cache
            fastlane # Mobile app publishing automation
            kotlin-language-server # Kotlin LSP
            lightningcss # CSS linter & optimizer
            scss-lint # SCSS linter
            taplo # TOML LSP
            typescript-language-server # TypeScript LSP
            vscode-langservers-extracted # HTML/CSS/JS(ON)
            yaml-language-server # YAML LSP
          ];
        in
        {
          default = env.pkgs.mkShell {
            packages = devTools ++ [
              (agents-jail.lib.${system}.mkOpencode {
                extraPkgs = devTools ++ env.commonNativeBuildInputs ++ env.androidNativeBuildInputs;
              })
            ];
            nativeBuildInputs = env.commonNativeBuildInputs ++ env.androidNativeBuildInputs;
            buildInputs = env.commonBuildInputs;
            ANDROID_HOME = "${env.androidComposition.androidsdk}/libexec/android-sdk";
            ANDROID_NDK_HOME = "${env.androidComposition.ndk-bundle}/libexec/android-sdk/ndk-bundle";
            LD_LIBRARY_PATH =
              with env.pkgs;
              lib.makeLibraryPath [
                stdenv.cc.cc.lib
                zlib
              ];
            shellHook = ''
              unset ANDROID_SDK_ROOT # Conflicts with Home in GitHub Runners
              export GRADLE_USER_HOME="$PWD/.gradle"
              export SE_CACHE_PATH="$PWD/.selenium"
              # If the system has a Temurin/Adoptium JDK with a broader CA trust store,
              # point the nix JVM at it so the Gradle wrapper can reach services.gradle.org
              for _cacerts in \
                /usr/lib/jvm/temurin-21-jdk-amd64/lib/security/cacerts \
                /usr/lib/jvm/temurin-17-jdk-amd64/lib/security/cacerts \
                /usr/lib/jvm/temurin-8-jdk-amd64/jre/lib/security/cacerts \
                /etc/ssl/certs/java/cacerts; do
                if [ -f "$_cacerts" ]; then
                  export JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=$_cacerts -Djavax.net.ssl.trustStorePassword=changeit"
                  break
                fi
              done
              export NIX_LD_LIBRARY_PATH="$LD_LIBRARY_PATH"
              export NIX_LD="$(cat $NIX_CC/nix-support/dynamic-linker)"
              export GRADLE_OPTS="''${GRADLE_OPTS:-} -Dorg.gradle.project.android.aapt2FromMavenOverride=$ANDROID_HOME/build-tools/34.0.0/aapt2"
              echo "✅ ${env.projectName} Dev Environment Ready"
              echo "- Android SDK $ANDROID_HOME"
              echo "- Android NDK $ANDROID_NDK_HOME"
            '';
          };
        }
      );
      checks = forAllSystems (
        system:
        let
          env = sharedEnvFor system;
        in
        {
          # format = env.pkgs.runCommand "${env.projectSlug}-fmt-${env.projectVersion}"
          #     {
          #       nativeBuildInputs = env.commonNativeBuildInputs;
          #     }
          #     ''
          #       cd ${self}
          #       TODO fmt check >> $out
          #     '';
          build = self.packages.${system}.default;
          # lint = TODO
          # coverage = TODO
          # default = env.pkgs.linkFarm "${env.projectSlug}-quick-checks" [
          #   {
          #     name = "format";
          #     path = self.checks.${system}.format;
          #   }
          #   {
          #     name = "coverage";
          #     path = self.checks.${system}.coverage;
          #   }
          #   {
          #     name = "lint";
          #     path = self.checks.${system}.lint;
          #   }
          # ];
        }
      );
    };
}
