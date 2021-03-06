package meghanada.server.emacs;

import com.google.common.base.Stopwatch;
import meghanada.server.CommandHandler;
import meghanada.server.OutputFormatter;
import meghanada.server.Server;
import meghanada.server.formatter.SexpOutputFormatter;
import meghanada.session.Session;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static com.leacox.motif.MatchesAny.any;
import static com.leacox.motif.MatchesExact.eq;
import static com.leacox.motif.Motif.match;
import static com.leacox.motif.cases.ListConsCases.headNil;
import static com.leacox.motif.cases.ListConsCases.headTail;
import static java.nio.charset.StandardCharsets.UTF_8;

public class EmacsServer implements Server {

    private static final Logger log = LogManager.getLogger(EmacsServer.class);
    private static final String EOT = ";;EOT";
    private final ServerSocket serverSocket;
    private final ExecutorService executorService = Executors.newFixedThreadPool(4);
    private final OUTPUT outputFormat;
    private final String projectRoot;
    private final String host;
    private final int port;
    private final boolean outputEOT;

    private Session session;

    public EmacsServer(final String host, final int port, final String projectRoot) throws IOException {
        final InetAddress address = InetAddress.getByName(host);
        this.host = host;
        this.port = port;
        this.serverSocket = new ServerSocket(port, 0, address);
        this.projectRoot = projectRoot;
        this.outputFormat = OUTPUT.SEXP;
        this.outputEOT = true;
    }

    @Override
    public void startServer() throws IOException {
        if (this.executorService.isShutdown()) {
            return;
        }
        if (this.executorService.isTerminated()) {
            return;
        }

        try {
            this.session = Session.createSession(projectRoot);
            this.session.start();
            log.info("Start server Listen {}:{}", this.host, this.port);
            this.accept();
        } catch (Throwable e) {
            log.catching(e);
        } finally {
            try {
                this.serverSocket.close();
                this.executorService.shutdownNow();
                if (this.session != null) {
                    this.session.shutdown(3);
                }
            } catch (Exception e) {
                log.catching(e);
            }
        }
    }

    private void accept() throws IOException {
        List<Future<?>> futures = new ArrayList<>();
        while (!this.serverSocket.isClosed()) {
            final Socket conn = this.serverSocket.accept();
            log.info("client connected");
            conn.setKeepAlive(true);
            final Future<?> future = acceptConnection(conn);
            futures.add(future);
        }

        futures.forEach((future) -> {
            try {
                future.get();
            } catch (InterruptedException | ExecutionException e) {
                log.catching(e);
            }
        });
    }

    private Future<?> acceptConnection(final Socket conn) {

        return this.executorService.submit(() -> {
            try (final BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), UTF_8));
                 final BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(conn.getOutputStream(), UTF_8))) {

                final CommandHandler handler = new CommandHandler(session, writer, getOutputFormatter());
                boolean start = true;
                final SExprParser parser = new SExprParser();
                while (start) {
                    final String line = reader.readLine();
                    if (line == null || line.isEmpty()) {
                        log.info("close from client ...");
                        break;
                    }
                    final SExprParser.SExpr expr = parser.parse(line);
                    final List<SExprParser.SExpr> lst = expr.value();
                    final List<String> args = lst.stream()
                            .map(sExpr -> sExpr.value().toString())
                            .collect(Collectors.toList());

                    log.debug("receive command line:{} expr:{} args:{}", line, expr, args);
                    start = this.dispatch(args, handler);
                    if (!start) {
                        log.info("stop client ... args:{}", args);
                    }
                    if (this.outputEOT) {
                        writer.write(EmacsServer.EOT);
                        writer.newLine();
                    }

                    writer.flush();
                }
                log.info("close client ...");
            } catch (Throwable e) {
                log.catching(e);
            } finally {
                try {
                    conn.close();
                } catch (IOException e) {
                    log.catching(e);
                }
                log.info("client disconnect");
            }
        });

    }

    private boolean dispatch(final List<String> argList, final CommandHandler handler) {
        final Stopwatch stopwatch = Stopwatch.createStarted();
        final boolean result = match(argList)
                .when(headTail(eq("pc"), any())).get(args -> {
                    // pc : Project Change
                    // usage: pc <filepath>
                    handler.changeProject(args.get(0));
                    return true;
                })
                .when(headTail(eq("p"), any())).get(args -> {
                    // p : Parse
                    // usage: p <filepath>
                    handler.parse(args.get(0));
                    return true;
                })
                .when(headTail(eq("ap"), any())).get(args -> {
                    // ap : Autocomplete Prefix
                    // usage: ap <filepath> <line> <column> <prefix> <fmt>
                    handler.autocomplete(args.get(0), args.get(1), args.get(2), args.get(3));
                    return true;
                })
                .when(headTail(eq("c"), any())).get(args -> {
                    // c : Compile
                    // usage: c <filepath>
                    handler.compile(args.get(0));
                    return true;
                })
                .when(headTail(eq("cp"), any())).get(args -> {
                    // cp : Compile Project
                    // usage: cp
                    handler.compileProject();
                    return true;
                })
                .when(headTail(eq("fc"), any())).get(args -> {
                    // fc : Format code
                    // usage: fc <filepath>
                    handler.formatCode(args.get(0));
                    return true;
                })
                .when(headTail(eq("di"), any())).get(args -> {
                    // di : Diagnostic
                    // usage: di <filepath>
                    handler.diagnostics(args.get(0));
                    return true;
                })
                .when(headTail(eq("rj"), any())).get(args -> {
                    // rj : Run JUnit Test
                    // usage: rj <testName>
                    if (args.isEmpty()) {
                        handler.runJUnit("");
                    } else {
                        handler.runJUnit(args.get(0));
                    }
                    return true;
                })
                .when(headTail(eq("rt"), any())).get(args -> {
                    // rj : Run Task
                    // usage: rt <args>
                    handler.runTask(args);
                    return true;
                })
                .when(headTail(eq("ai"), any())).get(args -> {
                    // ai : Add Import
                    // usage: ai <filepath> <import>
                    handler.addImport(args.get(0), args.get(1));
                    return true;
                })
                .when(headTail(eq("oi"), any())).get(args -> {
                    // oi : Optimize Import
                    // usage: oi <filepath>
                    handler.optimizeImport(args.get(0));
                    return true;
                })
                .when(headTail(eq("ia"), any())).get(args -> {
                    // ia : Import All
                    // usage: ia <filepath>
                    handler.importAll(args.get(0));
                    return true;
                })
                .when(headTail(eq("st"), any())).get(args -> {
                    // st : Switch test to src or src to test
                    // usage: st <filepath>
                    handler.switchTest(args.get(0));
                    return true;
                })
                .when(headTail(eq("jd"), any())).get(args -> {
                    // jd : Jump Declaration
                    // usage: jd <filepath> <line> <column> <symbol>
                    handler.jumpDeclaration(args.get(0), args.get(1), args.get(2), args.get(3));
                    return true;
                })
                .when(headTail(eq("sd"), any())).get(args -> {
                    // sd : Show declaration (short)
                    // usage: sd <filepath> <line> <column> <symbol>
                    handler.showDeclaration(args.get(0), args.get(1), args.get(2), args.get(3));
                    return true;
                })
                .when(headTail(eq("bj"), any())).get(args -> {
                    // bj : Back Jump
                    // usage: bj
                    handler.backJump();
                    return true;
                })
                .when(headTail(eq("cc"), any())).get(args -> {
                    // cc : Clear cache
                    // usage: cc
                    handler.clearCache();
                    return true;
                })
                .when(headTail(eq("lv"), any())).get(args -> {
                    // lv : Local variable
                    // usage: lv file line
                    handler.localVariable(args.get(0), args.get(1));
                    return true;
                })
                .when(headTail(eq("ping"), any())).get(args -> {
                    // st : Switch test to src or src to test
                    // usage: st <filepath>
                    handler.ping();
                    return true;
                })
                .when(headNil(eq("q"))).get(() -> {
                    // q : Quit session
                    // usage: q
                    return false;
                })
                .getMatch();

        log.info("receive command {}. elapsed:{}", argList, stopwatch.stop());
        return result;
    }

    private OutputFormatter getOutputFormatter() {
        if (this.outputFormat == OUTPUT.SEXP) {
            return new SexpOutputFormatter();
        }
        throw new UnsupportedOperationException("not support format");
    }

    private enum OUTPUT {
        SEXP,
        CSV,
        JSON,
    }

}
