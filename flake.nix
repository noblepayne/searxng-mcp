{
  description = "SearXNG MCP Server — Babashka Streamable HTTP MCP server for web search and URL-to-markdown conversion";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = {
    self,
    nixpkgs,
    flake-utils,
  }:
    flake-utils.lib.eachDefaultSystem (
      system: let
        pkgs = nixpkgs.legacyPackages.${system};
        bb = pkgs.babashka;

        searxng-mcp = pkgs.stdenv.mkDerivation {
          name = "searxng-mcp";
          version = "0.1.0";
          src = ./.;

          nativeBuildInputs = [bb];

          unpackPhase = "cp -r $src/* ./";
          buildPhase = "chmod +x searxng_mcp_bundled.clj";
          installPhase = ''
            mkdir -p $out/bin $out/share/searxng-mcp
            cp searxng_mcp_bundled.clj $out/share/searxng-mcp/
            cat > $out/bin/searxng-mcp << 'EOF'
            #!/usr/bin/env bash
            exec ${bb}/bin/bb ${placeholder "out"}/share/searxng-mcp/searxng_mcp_bundled.clj "$@"
            EOF
            chmod +x $out/bin/searxng-mcp
          '';

          meta = with pkgs.lib; {
            description = "SearXNG MCP Server for web search and URL reading";
            homepage = "https://github.com/JupiterBroadcasting/J.O.E.";
            license = licenses.mit;
            platforms = platforms.linux;
          };
        };
      in {
        formatter = pkgs.alejandra;
        packages = {
          default = searxng-mcp;
          inherit searxng-mcp;
        };

        devShells.default = pkgs.mkShell {
          buildInputs = [bb pkgs.clj-kondo pkgs.jq pkgs.carve];
          shellHook = ''
            echo "searxng-mcp dev shell"
            echo "  bb run      — start server (OS-assigned port)"
            echo "  bb start    — start server on port 3009"
            echo "  bb test     — run tests"
            echo "  bb lint     — clj-kondo lint"
            echo ""
            echo "Config: SEARXNG_URL (default: http://prism:8888)"
            echo "        JINA_API_KEY (optional)"
          '';
        };
      }
    )
    // {
      nixosModules.default = {
        config,
        lib,
        pkgs,
        ...
      }: let
        cfg = config.services.searxng-mcp;
        bb = pkgs.babashka;
        searxng-mcp-pkg = pkgs.stdenv.mkDerivation {
          name = "searxng-mcp";
          src = ./.;
          nativeBuildInputs = [bb];
          unpackPhase = "cp -r $src/* ./";
          buildPhase = "chmod +x searxng_mcp_bundled.clj";
          installPhase = ''
            mkdir -p $out/bin $out/share/searxng-mcp
            cp searxng_mcp_bundled.clj $out/share/searxng-mcp/
            cat > $out/bin/searxng-mcp << EOF
            #!/usr/bin/env bash
            exec ${bb}/bin/bb $out/share/searxng-mcp/searxng_mcp_bundled.clj "\$@"
            EOF
            chmod +x $out/bin/searxng-mcp
          '';
        };
      in {
        options.services.searxng-mcp = {
          enable = lib.mkEnableOption "SearXNG MCP Server";

          port = lib.mkOption {
            type = lib.types.int;
            default = 3009;
            description = "Port for the SearXNG MCP server to listen on.";
          };

          host = lib.mkOption {
            type = lib.types.str;
            default = "127.0.0.1";
            description = "Host address to bind to.";
          };

          openFirewall = lib.mkOption {
            type = lib.types.bool;
            default = false;
            description = "Open the firewall for the MCP server port.";
          };

          searxngUrl = lib.mkOption {
            type = lib.types.str;
            default = "http://prism:8888";
            description = "URL of the SearXNG instance to query.";
          };

          jinaApiKeyFile = lib.mkOption {
            type = lib.types.nullOr lib.types.path;
            default = null;
            description = ''
              Path to a file containing the Jina Reader API key in the format:
                JINA_API_KEY=your-key
              Keep this file outside the Nix store (e.g. /run/secrets/jina-api-key).
              Optional — without it, Jina Reader uses the free anonymous tier.
            '';
          };

          user = lib.mkOption {
            type = lib.types.str;
            default = "searxng-mcp";
            description = "User to run the service as.";
          };

          group = lib.mkOption {
            type = lib.types.str;
            default = "searxng-mcp";
            description = "Group to run the service as.";
          };
        };

        config = lib.mkIf cfg.enable {
          networking.firewall.allowedTCPPorts = lib.mkIf cfg.openFirewall [cfg.port];

          users.users.${cfg.user} = {
            isSystemUser = true;
            group = cfg.group;
            description = "SearXNG MCP Server";
          };
          users.groups.${cfg.group} = {};

          systemd.services.searxng-mcp = {
            description = "SearXNG MCP Server";
            wantedBy = ["multi-user.target"];
            after = ["network.target"];

            serviceConfig = {
              Type = "simple";
              User = cfg.user;
              Group = cfg.group;
              ExecStart = "${searxng-mcp-pkg}/bin/searxng-mcp";
              Restart = "on-failure";
              RestartSec = "5s";

              Environment =
                [
                  "SEARXNG_MCP_PORT=${toString cfg.port}"
                  "SEARXNG_MCP_HOST=${cfg.host}"
                  "SEARXNG_URL=${cfg.searxngUrl}"
                ]
                ++ lib.optionals (cfg.jinaApiKeyFile != null) [
                  "EnvironmentFile=${cfg.jinaApiKeyFile}"
                ];

              # Hardening
              NoNewPrivileges = true;
              PrivateTmp = true;
              ProtectSystem = "strict";
              ProtectHome = "read-only";
            };
          };
        };
      };
    };
}
