package com.archivist.command.commands;

import com.archivist.command.Command;
import com.archivist.data.ServerSession;
import com.archivist.util.ArchivistExecutor;
import com.archivist.util.IpInfoLookup;

import java.util.function.Consumer;
import java.util.function.Supplier;

public final class IpInfoCommand implements Command {

    private final Supplier<ServerSession> sessionSupplier;
    private final IpInfoLookup ipInfoLookup = new IpInfoLookup();

    public IpInfoCommand(Supplier<ServerSession> sessionSupplier) {
        this.sessionSupplier = sessionSupplier;
    }

    @Override
    public String name() {
        return "ipinfo";
    }

    @Override
    public String description() {
        return "Lookup IP info for current server via ipinfo.io";
    }

    @Override
    public void execute(String[] args, Consumer<String> output) {
        ServerSession session = sessionSupplier.get();
        String address;

        if (args.length > 0 && !args[0].isBlank()) {
            address = args[0];
        } else if (session != null) {
            address = session.getDomain();
            if (address == null || address.equals("unknown")) {
                output.accept("No server address available. Provide an IP/domain as argument.");
                return;
            }
        } else {
            output.accept("Not connected to any server. Provide an IP/domain as argument.");
            return;
        }

        output.accept("Looking up IP info for: " + address + " ...");

        ArchivistExecutor.execute(() -> {
            IpInfoLookup.IpInfoResult result = ipInfoLookup.lookup(address);

            if (result.isSuccess()) {
                output.accept("=== IP Info ===");
                output.accept("  Domain: " + result.rawDomain());
                output.accept("  IP: " + result.queriedIp());
                output.accept("  Response:");
                output.accept(prettifyJson(result.ipInfoJson()));
            } else {
                output.accept("Error: " + result.error());
            }
        });
    }

    private String prettifyJson(String json) {
        if (json == null) return "null";
        return json
                .replace("{", "")
                .replace("}", "")
                .replace("\"", "")
                .replace(",", "\n  ");
    }
}
