package app.plait.broker.server;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the command-line tools apps run are and do: every BusyBox applet (Android ships most of them as toybox
 * applets of the same name and options) plus Android's own tools. Used to
 * <ul>
 *   <li>group commands in the manager by tool, and for file tools by where they act: privileged locations only the
 *       shell can reach, or ordinary ones any app may read ({@link #privileged});</li>
 *   <li>explain a command's options ({@link #explain});</li>
 *   <li>classify what a tool or option can do beyond its tool's usual job ({@link #classify}): run other programs, run a
 *       server, need root, show other apps' processes, stop processes.</li>
 * </ul>
 * Pure Java, shared by the daemon and the manager.
 *
 * <p>Option specs: {@code "-n=:lines to show"}. A trailing {@code =} takes a value; {@code @} after it marks the value
 * as a file the tool writes, {@code <} as a file it reads; {@code +} marks an option that makes the tool write files;
 * {@code !} an option that runs other programs or code (never allowed).
 */
public final class Tools {
    public enum Category {
        FILES("Files"),
        TEXT("Text"),
        ARCHIVES("Archives and compression"),
        NETWORK("Network"),
        PROCESSES("Processes"),
        DEVICE("Device and system information"),
        ANDROID("Android"),
        ADMIN("System administration (needs root)"),
        LAUNCHERS("Runs other programs"),
        OTHER("Other");

        public final String title;

        Category(String title) {
            this.title = title;
        }
    }

    /** Which arguments are paths. */
    enum Paths {
        NONE,
        /** every operand */
        ALL,
        /** every operand after the first (a pattern or script), unless an option supplied it */
        AFTER_FIRST,
        /** operands before the first expression word (find) */
        FIND,
        /** if=/of= operands (dd) */
        DD
    }

    /** What a tool does, for the tier it gets. */
    enum Effect {
        READ, WRITE, INFO, NETWORK,
        /** shows other apps' processes, connections or the kernel log: asked once */
        PRIVACY,
        /** stops or reprioritizes processes of the shell user (the broker itself, adb): gated */
        CONTROL,
        /** needs root; the shell cannot do it, so it is refused instead of failing oddly */
        ROOT,
        /** runs a network server as the shell */
        SERVER,
        /** runs other programs or code */
        LAUNCH,
        /** restarts or powers off the phone: gated */
        POWER
    }

    static final class Option {
        final String description;
        final boolean takesValue;
        final boolean writesValue;
        final boolean readsValue;
        final boolean writes;
        final boolean launches;

        Option(String spec) {
            int colon = spec.indexOf(':');
            String marks = spec.substring(0, colon);
            description = spec.substring(colon + 1);
            takesValue = marks.contains("=");
            writesValue = marks.contains("@");
            readsValue = marks.contains("<");
            writes = marks.contains("+");
            launches = marks.contains("!");
        }
    }

    public static final class Tool {
        public final String name;
        public final String title;
        public final Category category;
        final Effect effect;
        final Paths paths;
        final Map<String, Option> options = new LinkedHashMap<>();

        Tool(String name, String title, Category category, Effect effect, Paths paths, String... options) {
            this.name = name;
            this.title = title;
            this.category = category;
            this.effect = effect;
            this.paths = paths;
            for (String spec : options) {
                String flag = spec.substring(0, firstMark(spec));
                this.options.put(flag, new Option(spec.substring(flag.length())));
            }
        }

        private static int firstMark(String spec) {
            for (int i = 1; i < spec.length(); i++) {
                if ("=@<+!:".indexOf(spec.charAt(i)) >= 0) return i;
            }
            return spec.length();
        }

        boolean isFileTool() {
            return paths != Paths.NONE;
        }
    }

    private static final Map<String, Tool> TOOLS = new HashMap<>();

    private static void add(String name, String title, Category c, Effect e, Paths p, String... options) {
        TOOLS.put(name, new Tool(name, title, c, e, p, options));
    }

    /** Several tools that share a title, effect and options. */
    private static void addAll(String names, String title, Category c, Effect e, Paths p, String... options) {
        for (String n : names.split(" ")) add(n, title, c, e, p, options);
    }

    private static final String[] HASH_OPTS = {"-c<:check sums listed in a file", "-b:binary mode", "-s:status only"};
    private static final String[] COMPRESS_OPTS = {"-c:write to standard output", "-d:decompress", "-f:force",
            "-k:keep the input files", "-t:test integrity", "-v:verbose", "-1:fastest", "-9:smallest", "-q:quiet"};

    static {
        Category F = Category.FILES, T = Category.TEXT, A = Category.ARCHIVES, N = Category.NETWORK,
                P = Category.PROCESSES, D = Category.DEVICE, AN = Category.ANDROID, R = Category.ADMIN,
                L = Category.LAUNCHERS;
        Effect READ = Effect.READ, WRITE = Effect.WRITE, INFO = Effect.INFO, NET = Effect.NETWORK;

        // ---------------------------------------------------------------- reading files
        add("cat", "Read files", F, READ, Paths.ALL, "-n:number lines", "-b:number non-blank lines",
                "-s:squeeze blank lines", "-v:show non-printing characters", "-e:mark line ends", "-t:show tabs",
                "-A:show everything", "-u:unbuffered");
        add("head", "Read the start of files", F, READ, Paths.ALL, "-n=:lines", "-c=:bytes", "-q:no file names",
                "-v:always file names");
        add("tail", "Read the end of files", F, READ, Paths.ALL, "-n=:lines", "-c=:bytes", "-f:follow",
                "-F:follow by name", "-q:no file names", "-s=:seconds between checks", "-v:always file names");
        add("more", "Page through files", F, READ, Paths.ALL);
        add("less", "Page through files", L, Effect.LAUNCH, Paths.ALL); // "!command" runs a shell
        addAll("od hexdump hd xxd", "Show file bytes", F, READ, Paths.ALL, "-A=:address base", "-t=:format",
                "-N=:bytes", "-j=:skip bytes", "-C:canonical hex+ASCII", "-c:characters", "-x:hex words",
                "-v:no duplicate suppression", "-n=:bytes", "-s=:seek", "-l=:length", "-p:plain hex", "-r+:reverse to binary");
        add("strings", "Find text in files", F, READ, Paths.ALL, "-a:whole file", "-n=:minimum length",
                "-t=:show offsets", "-o:show offsets", "-f:file names");
        add("wc", "Count lines, words and bytes", F, READ, Paths.ALL, "-l:lines", "-w:words", "-c:bytes",
                "-m:characters", "-L:longest line");
        addAll("md5sum sha1sum sha256sum sha512sum sha3sum", "Checksum files", F, READ, Paths.ALL, HASH_OPTS);
        addAll("crc32 cksum sum", "Checksum files", F, READ, Paths.ALL, "-r:BSD algorithm", "-s:System V algorithm");
        add("stat", "Show file details", F, READ, Paths.ALL, "-c=:format", "-f:file system", "-L:follow links",
                "-t:terse");
        add("file", "Identify file types", F, READ, Paths.ALL, "-b:brief", "-L:follow links", "-h:do not follow links");
        add("readlink", "Resolve links", F, READ, Paths.ALL, "-f:canonical, all but last may exist",
                "-e:canonical, all must exist", "-m:canonical, none must exist", "-n:no newline", "-v:verbose");
        add("realpath", "Resolve paths", F, READ, Paths.ALL, "-e:must exist", "-m:may be missing", "-s:no links",
                "-q:quiet");
        add("cmp", "Compare files", F, READ, Paths.ALL, "-l:list differences", "-s:silent", "-n=:bytes");
        add("diff", "Compare files", F, READ, Paths.ALL, "-u:unified", "-U=:context lines", "-r:recursive",
                "-N:missing files are empty", "-q:only whether they differ", "-a:treat as text", "-b:ignore space changes",
                "-w:ignore all space", "-B:ignore blank lines", "-i:ignore case", "-s:report identical", "-t:expand tabs",
                "-L=:label", "-S=:start with file", "-x=:exclude pattern", "-T:align tabs", "-d:smallest diff");
        addAll("zcat bzcat xzcat lzcat lzopcat unlzop", "Read compressed files", A, READ, Paths.ALL, "-f:force");
        // ---------------------------------------------------------------- browsing folders
        add("ls", "List files", F, READ, Paths.ALL, "-a:all entries", "-A:almost all", "-l:long format",
                "-1:one per line", "-R:recursive", "-d:folders themselves", "-h:human sizes", "-i:inode numbers",
                "-n:numeric ids", "-s:sizes", "-S:sort by size", "-t:sort by time", "-r:reverse", "-c:by change time",
                "-u:by access time", "-F:mark types", "-p:mark folders", "-L:follow links", "-H:follow command-line links",
                "-Z:security context", "-k:1K blocks", "-x:across", "-C:columns", "-m:comma separated", "-q:hide control chars",
                "-e:full times", "-g:no owner", "-o:no group", "-X:by extension", "-U:unsorted", "-f:unsorted, all",
                "--color=:colors", "--full-time:full times", "--group-directories-first:folders first");
        add("find", "Find files", F, READ, Paths.FIND, "-name=:name pattern", "-iname=:name pattern, any case",
                "-path=:path pattern", "-ipath=:path pattern, any case", "-type=:file type", "-maxdepth=:max depth",
                "-mindepth=:min depth", "-size=:size", "-mtime=:modified days", "-mmin=:modified minutes",
                "-newer<:newer than a file", "-user=:owner", "-group=:group", "-perm=:permissions", "-links=:link count",
                "-inum=:inode", "-regex=:regex", "-empty:empty", "-print:print", "-print0:print with NULs",
                "-prune:skip folder", "-xdev:one file system", "-follow:follow links", "-L:follow links",
                "-H:follow command-line links", "-quit:stop at first", "-context=:security context",
                "-exec!:runs a program for each file", "-execdir!:runs a program for each file", "-ok!:runs a program",
                "-okdir!:runs a program", "-delete+:deletes the files found", "-o:or", "-or:or", "-a:and", "-and:and",
                "-not:not", "-true:always", "-false:never", "-ls:list details", "-printf=:print format");
        add("du", "Measure folder sizes", F, READ, Paths.ALL, "-a:all files", "-s:summary", "-h:human sizes",
                "-k:1K blocks", "-m:1M blocks", "-c:total", "-d=:max depth", "-x:one file system", "-L:follow links",
                "-l:count hard links");
        add("tree", "Show folder tree", F, READ, Paths.ALL);
        add("lsattr", "Show file attributes", F, READ, Paths.ALL, "-R:recursive", "-a:all", "-d:folders themselves",
                "-l:long names", "-v:version");
        add("which", "Find programs", F, INFO, Paths.NONE, "-a:all matches");
        add("pwd", "Show current folder", F, INFO, Paths.NONE, "-L:logical", "-P:physical");
        add("mountpoint", "Check mount points", F, READ, Paths.ALL, "-q:quiet", "-d:device number", "-x:block device");
        // ---------------------------------------------------------------- changing files
        add("cp", "Copy files", F, WRITE, Paths.ALL, "-r:recursive", "-R:recursive", "-a:archive", "-p:preserve",
                "-f:force", "-i:ask", "-n:no overwrite", "-l:hard links", "-s:symbolic links", "-u:only newer",
                "-v:verbose", "-d:keep links", "-L:follow links", "-P:keep links", "-H:follow command-line links",
                "-t=@:into folder", "-T:target is a file", "--preserve=:attributes to keep");
        add("mv", "Move files", F, WRITE, Paths.ALL, "-f:force", "-i:ask", "-n:no overwrite", "-u:only newer",
                "-v:verbose", "-t=@:into folder", "-T:target is a file");
        add("rm", "Delete files", F, WRITE, Paths.ALL, "-r:recursive", "-R:recursive", "-f:force", "-i:ask",
                "-v:verbose", "-d:empty folders");
        add("rmdir", "Delete folders", F, WRITE, Paths.ALL, "-p:parents", "--ignore-fail-on-non-empty:ignore non-empty");
        add("mkdir", "Make folders", F, WRITE, Paths.ALL, "-p:parents", "-m=:mode", "-v:verbose", "-Z=:context");
        add("touch", "Create or date files", F, WRITE, Paths.ALL, "-a:access time", "-m:modify time", "-c:no create",
                "-d=:date", "-t=:time", "-r<:time of a file", "-h:links themselves");
        add("ln", "Link files", F, WRITE, Paths.ALL, "-s:symbolic", "-f:force", "-n:no dereference", "-v:verbose",
                "-T:target is a file", "-t=@:into folder", "-r:relative");
        addAll("chmod chown chgrp", "Change file permissions or owners", F, WRITE, Paths.AFTER_FIRST, "-R:recursive",
                "-v:verbose", "-c:report changes", "-f:quiet", "-h:links themselves", "-H:follow command-line links",
                "-L:follow links", "-P:no links", "--reference<:from a file");
        add("chattr", "Change file attributes", F, WRITE, Paths.AFTER_FIRST, "-R:recursive", "-v=:version");
        add("chcon", "Change security contexts", F, WRITE, Paths.AFTER_FIRST, "-R:recursive", "-v:verbose", "-h:links");
        add("dd", "Copy raw data", F, WRITE, Paths.DD);
        add("truncate", "Resize files", F, WRITE, Paths.ALL, "-s=:size", "-c:no create", "-o:size in blocks");
        add("tee", "Copy input to files", F, WRITE, Paths.ALL, "-a:append", "-i:ignore interrupts");
        add("install", "Copy files with permissions", F, WRITE, Paths.ALL, "-c:copy", "-d:folders", "-D:make parents",
                "-m=:mode", "-o=:owner", "-g=:group", "-p:preserve times", "-s:strip", "-t=@:into folder", "-v:verbose");
        add("shred", "Overwrite and delete files", F, WRITE, Paths.ALL, "-f:force", "-n=:passes", "-u:delete",
                "-z:zero last", "-s=:size", "-x:exact size");
        addAll("mkfifo mktemp", "Make special or temporary files", F, WRITE, Paths.ALL, "-m=:mode", "-d:folder",
                "-q:quiet", "-u:name only", "-p=@:in folder", "-t:in TMPDIR");
        add("split", "Split files", F, WRITE, Paths.ALL, "-b=:bytes per piece", "-l=:lines per piece",
                "-a=:suffix length", "-d:numeric suffixes");
        add("patch", "Apply patches", F, WRITE, Paths.ALL, "-p=:strip path parts", "-i=<:patch file", "-R:reverse",
                "-N:ignore applied", "-E:remove empty", "-u:unified", "-s:silent", "-d=:in folder", "--dry-run:only check",
                "-g=:get");
        add("unlink", "Delete a file", F, WRITE, Paths.ALL);
        add("fallocate", "Reserve file space", F, WRITE, Paths.ALL, "-l=:length", "-o=:offset");
        addAll("sync fsync", "Flush files to storage", F, INFO, Paths.ALL, "-d:data only", "-f:file system");
        add("uudecode", "Decode files", F, WRITE, Paths.ALL, "-o=@:output file");
        add("uuencode", "Encode files", T, READ, Paths.ALL, "-m:base64");
        // ---------------------------------------------------------------- text
        addAll("echo printf", "Print text", T, INFO, Paths.NONE, "-n:no newline", "-e:interpret escapes",
                "-E:no escapes");
        add("sort", "Sort lines", T, READ, Paths.ALL, "-n:numeric", "-r:reverse", "-u:unique", "-k=:key",
                "-t=:field separator", "-f:ignore case", "-b:ignore leading blanks", "-h:human numbers", "-V:versions",
                "-g:general numbers", "-M:months", "-s:stable", "-c:check", "-C:check quietly", "-m:merge", "-z:NUL lines",
                "-R:random", "-o=@:write the result to a file", "-T=:temporary folder", "-S=:buffer size",
                "--compress-program=!:runs a program to compress temporary files");
        add("uniq", "Remove duplicate lines", T, READ, Paths.ALL, "-c:count", "-d:only duplicates", "-u:only unique",
                "-i:ignore case", "-f=:skip fields", "-s=:skip characters", "-w=:compare characters", "-z:NUL lines");
        add("cut", "Cut columns", T, READ, Paths.ALL, "-b=:bytes", "-c=:characters", "-f=:fields",
                "-d=:delimiter", "-s:only delimited lines", "-n:ignored", "-D:no sorting", "-O=:output delimiter");
        add("tr", "Translate characters", T, INFO, Paths.NONE, "-d:delete", "-s:squeeze", "-c:complement",
                "-C:complement", "-t:truncate");
        add("sed", "Edit text", L, Effect.LAUNCH, Paths.AFTER_FIRST, "-n:quiet", "-e=:script", "-f=<:script file",
                "-E:extended regex", "-r:extended regex", "-s:separate files", "-z:NUL lines", "-i+:edit files in place");
        add("awk", "Process text with programs", L, Effect.LAUNCH, Paths.AFTER_FIRST, "-F=:field separator",
                "-v=:variable", "-f=<:program file");
        addAll("grep egrep fgrep", "Search text", T, READ, Paths.AFTER_FIRST, "-i:ignore case", "-v:invert",
                "-c:count", "-l:file names with matches", "-L:file names without matches", "-n:line numbers",
                "-H:file names", "-h:no file names", "-o:only matching part", "-q:quiet", "-s:no errors", "-r:recursive",
                "-R:recursive, follow links", "-w:whole words", "-x:whole lines", "-E:extended regex", "-F:fixed strings",
                "-P:Perl regex", "-z:NUL lines", "-a:treat as text", "-I:skip binary", "-m=:max matches",
                "-A=:lines after", "-B=:lines before", "-C=:lines around", "-e=:pattern", "-f=<:patterns file",
                "--include=:only files matching", "--exclude=:skip files matching", "--exclude-dir=:skip folders matching",
                "--color=:colors", "--line-buffered:line buffered", "-b:byte offsets", "-Z:NUL after names");
        add("paste", "Join lines side by side", T, READ, Paths.ALL, "-d=:delimiters", "-s:serial");
        add("fold", "Wrap lines", T, READ, Paths.ALL, "-w=:width", "-b:bytes", "-s:at spaces");
        add("nl", "Number lines", T, READ, Paths.ALL, "-b=:which lines", "-n=:format", "-s=:separator",
                "-v=:start", "-w=:width", "-i=:increment", "-E:extended regex");
        addAll("rev tac", "Reverse lines", T, READ, Paths.ALL);
        addAll("expand unexpand", "Convert tabs and spaces", T, READ, Paths.ALL, "-t=:tab stops", "-i:only leading",
                "-a:all blanks", "-f:only leading");
        add("comm", "Compare sorted files", T, READ, Paths.ALL, "-1:hide first", "-2:hide second", "-3:hide common");
        add("seq", "Print number sequences", T, INFO, Paths.NONE, "-w:equal width", "-s=:separator", "-f=:format");
        add("yes", "Repeat text", T, INFO, Paths.NONE);
        addAll("base64 base32", "Encode or decode text", T, READ, Paths.ALL, "-d:decode", "-w=:wrap", "-i:ignore garbage");
        addAll("dos2unix unix2dos", "Convert line endings", T, WRITE, Paths.ALL, "-u:to Unix", "-d:to DOS");
        addAll("tsort shuf", "Order lines", T, READ, Paths.ALL, "-n=:lines", "-e:arguments as lines",
                "-i=:range", "-o=@:output file", "-z:NUL lines");
        addAll("factor expr test [ [[ true false sleep usleep", "Shell helpers", T, INFO, Paths.NONE);
        addAll("basename dirname", "Path names", T, INFO, Paths.NONE, "-s=:suffix", "-a:several names", "-z:NUL ends");
        addAll("bc dc", "Calculate", T, INFO, Paths.NONE, "-l:math library", "-q:quiet", "-s:POSIX", "-w:warn",
                "-e=:expression");
        addAll("vi ed man", "Edit or view text interactively", L, Effect.LAUNCH, Paths.ALL);
        // ---------------------------------------------------------------- archives
        add("tar", "Archive files", A, READ, Paths.ALL, "-c+:create an archive", "-x+:extract files", "-t:list",
                "-r+:append", "-u+:update", "-f=<:archive file", "-z:gzip", "-j:bzip2", "-J:xz", "-a:by extension",
                "-v:verbose", "-C=@:change to folder", "-O:extract to standard output", "-p:keep permissions",
                "-h:follow links", "-m:no times", "-k:keep existing", "-T=<:names from a file", "-X=<:exclude list from a file",
                "--exclude=:exclude pattern", "--strip-components=:strip path parts", "--no-same-owner:no owners",
                "--numeric-owner:numeric owners", "--overwrite:overwrite", "--to-command=!:runs a program for each file",
                "-I=!:runs a program to compress", "--use-compress-program=!:runs a program to compress");
        addAll("gzip gunzip bzip2 bunzip2 xz unxz lzma unlzma lzop lzip compress uncompress", "Compress or decompress",
                A, WRITE, Paths.ALL, COMPRESS_OPTS);
        add("unzip", "Extract zip files", A, WRITE, Paths.ALL, "-l:list", "-v:list verbose", "-t:test",
                "-p:to standard output", "-o:overwrite", "-n:never overwrite", "-q:quiet", "-j:flatten",
                "-d=@:into folder", "-x:exclude");
        add("cpio", "Archive files", A, WRITE, Paths.ALL, "-i+:extract", "-o:create", "-p+:copy", "-t:list",
                "-d:make folders", "-m:keep times", "-u:overwrite", "-v:verbose", "-F=<:archive file", "-H=:format",
                "-0:NUL names", "-L:follow links");
        addAll("ar rpm2cpio rpm dpkg dpkg-deb", "Package archives", A, WRITE, Paths.ALL, "-x+:extract", "-t:list",
                "-p:to standard output", "-v:verbose", "-o:keep times", "-c:contents", "-e+:extract control files",
                "-f:fields", "-X+:extract with names", "-i+:install", "-l:list", "-q:query");
        // ---------------------------------------------------------------- network
        add("ip", "Network", N, NET, Paths.NONE, "-4:IPv4", "-6:IPv6", "-o:one line", "-s:statistics",
                "-d:details", "-c:color", "-br:brief", "-j:JSON", "-f=:family", "-n=:namespace");
        addAll("ifconfig route arp", "Network", N, NET, Paths.NONE, "-a:all interfaces", "-n:numeric", "-e:extended",
                "-v:verbose", "-A=:family");
        addAll("ipaddr iplink iproute ipneigh iprule iptunnel", "Network", N, NET, Paths.NONE);
        addAll("netstat ss", "Network connections", N, Effect.PRIVACY, Paths.NONE, "-a:all", "-l:listening",
                "-t:TCP", "-u:UDP", "-w:raw", "-x:Unix", "-n:numeric", "-p:programs", "-e:extended", "-r:routes",
                "-W:wide", "-s:summary", "-o:timers");
        addAll("ping ping6", "Ping", N, NET, Paths.NONE, "-c=:count", "-i=:interval", "-W=:timeout", "-w=:deadline",
                "-s=:size", "-t=:TTL", "-q:quiet", "-4:IPv4", "-6:IPv6", "-I=:interface", "-n:numeric", "-A:adaptive",
                "-f:flood", "-p=:pattern");
        addAll("traceroute traceroute6 tracepath", "Trace a network route", N, NET, Paths.NONE, "-n:numeric",
                "-m=:max hops", "-q=:probes", "-w=:wait", "-I:ICMP", "-4:IPv4", "-6:IPv6", "-i=:interface", "-p=:port");
        addAll("nslookup host dig whois hostname dnsdomainname ipcalc", "Names and addresses", N, NET, Paths.NONE,
                "-type=:record type", "-s:short", "-f:full", "-i:addresses", "-d:domain", "-b:broadcast", "-n:network",
                "-p:prefix", "-m:netmask");
        add("arping", "Ping on the local network", N, NET, Paths.NONE, "-c=:count", "-I=:interface", "-D:detect duplicates",
                "-U:unsolicited", "-A:reply", "-f:first reply", "-q:quiet", "-w=:deadline", "-s=:source");
        addAll("wget curl", "Download", N, NET, Paths.NONE, "-O=@:save as", "-o=@:log to file", "-P=@:into folder",
                "-q:quiet", "-c:continue", "-S:show headers", "-T=:timeout", "-t=:tries", "-U=:user agent",
                "--spider:only check", "--header=:header", "--post-data=:POST data", "--post-file=<:POST a file",
                "--no-check-certificate:skip certificate checks", "-L:follow redirects", "-s:silent", "-I:headers only",
                "-X=:method", "-d=:data", "-H=:header", "-k:skip certificate checks", "-f:fail silently");
        add("nc", "Raw network connections", N, NET, Paths.NONE, "-l:listen (a server)", "-p=:port", "-u:UDP",
                "-w=:timeout", "-z:scan only", "-v:verbose", "-n:numeric", "-s=:source", "-q=:quit after",
                "-e=!:runs a program on the connection", "-c=!:runs a shell command on the connection",
                "-L:listen forever (a server)");
        addAll("telnet ftpget ftpput tftp ssl_client sendmail popmaildir", "Network clients", N, NET, Paths.ALL,
                "-u=:user", "-p=:password", "-P=:port", "-g:get", "-p:put", "-l=:local file", "-r=:remote file",
                "-b=:block size", "-v:verbose");
        addAll("httpd telnetd ftpd inetd tcpsvd udpsvd dnsd udhcpd ntpd", "Network servers", N, Effect.SERVER, Paths.NONE);
        addAll("ifup ifdown udhcpc dhcprelay brctl ether-wake nameif tunctl vconfig slattach ifenslave iptables ip6tables",
                "Network configuration", N, Effect.ROOT, Paths.NONE);
        // ---------------------------------------------------------------- processes
        addAll("ps top pgrep pidof pstree pmap pwdx fuser lsof iotop", "Processes", P, Effect.PRIVACY, Paths.NONE,
                "-A:all", "-e:all", "-f:full", "-l:long", "-o=:columns", "-p=:process ids", "-u=:users", "-t=:terminals",
                "-T:threads", "-Z:security context", "-w:wide", "-n=:iterations", "-d=:delay", "-b:batch", "-m=:max lines",
                "-x:exact", "-s:single", "-q:quiet", "-a:full command");
        addAll("kill killall killall5 pkill renice", "Stop or reprioritize processes", P, Effect.CONTROL, Paths.NONE,
                "-s=:signal", "-l:list signals", "-9:kill", "-15:terminate", "-HUP:hang up", "-n=:priority",
                "-p:process ids", "-g:groups", "-u:users");
        addAll("uptime free vmstat iostat mpstat nmeter powertop", "System load and memory", D, INFO, Paths.NONE,
                "-b:bytes", "-k:KiB", "-m:MiB", "-g:GiB", "-h:human", "-t:totals", "-s=:seconds", "-c=:count", "-p:pretty");
        // ---------------------------------------------------------------- device
        addAll("uname arch nproc hostid tty stty getconf", "Device information", D, INFO, Paths.NONE, "-a:all",
                "-s:kernel name", "-n:host name", "-r:kernel release", "-v:kernel version", "-m:machine", "-o:OS",
                "-p:processor", "-i:hardware platform", "--all:all CPUs");
        addAll("id whoami groups logname", "Who the shell is", D, INFO, Paths.NONE, "-u:user id", "-g:group id",
                "-G:all groups", "-n:names", "-r:real ids", "-Z:security context");
        addAll("date cal", "Date and time", D, INFO, Paths.NONE, "-u:UTC", "-R:RFC 2822", "-I=:ISO 8601",
                "-d=:show a date", "-r<:file's time", "-D=:input format", "-s=:set the time (needs root)");
        add("df", "Storage space", D, INFO, Paths.NONE, "-h:human", "-k:1K blocks", "-m:1M blocks", "-P:POSIX",
                "-a:all", "-i:inodes", "-T:types", "-t=:type");
        add("printenv", "Environment", D, INFO, Paths.NONE, "-0:NUL ends");
        addAll("lsusb lspci lsscsi lsmod blkid findfs modinfo ipcs getenforce getsebool", "Hardware and kernel", D, INFO,
                Paths.NONE, "-v:verbose", "-k:kernel drivers", "-n:numeric", "-m:machine-readable", "-a:all",
                "-q:quiet", "-s:short");
        add("dmesg", "Kernel log", D, Effect.PRIVACY, Paths.NONE, "-c+:clear the log", "-n=:console level",
                "-s=:buffer size", "-r:raw", "-t:no times", "-w:follow");
        // ---------------------------------------------------------------- Android's own tools
        add("getprop", "System properties", AN, INFO, Paths.NONE, "-T:types", "-Z:security contexts");
        add("setprop", "Change system properties", AN, Effect.WRITE, Paths.NONE);
        add("settings", "Settings", AN, INFO, Paths.NONE);
        add("pm", "Packages", AN, INFO, Paths.NONE);
        add("am", "Activities and services", AN, INFO, Paths.NONE);
        add("cmd", "System services", AN, INFO, Paths.NONE);
        add("appops", "App ops", AN, INFO, Paths.NONE);
        add("dumpsys", "System service state", AN, INFO, Paths.NONE);
        add("wm", "Display size and density", AN, INFO, Paths.NONE);
        add("svc", "Power, Wi-Fi, data and USB switches", AN, INFO, Paths.NONE);
        add("logcat", "System log", AN, Effect.PRIVACY, Paths.NONE);
        addAll("input monkey screencap screenrecord uiautomator", "Screen and input", AN, INFO, Paths.NONE);
        addAll("content service dpm ime telecom bmgr bu", "System services", AN, INFO, Paths.NONE);
        // ---------------------------------------------------------------- root-only administration
        addAll("mount umount swapon swapoff losetup insmod rmmod modprobe depmod fdisk mkfs mkfs.ext2 mkfs.vfat mke2fs "
                + "mkswap fsck fsck.minix e2fsck tune2fs hwclock sysctl pivot_root switch_root init adduser addgroup "
                + "deluser delgroup passwd chpasswd cryptpw login getty sulogin vlock crond crontab syslogd klogd setfont "
                + "loadkmap chvt openvt deallocvt fbset mdev partprobe blockdev setserial rtcwake nandwrite nanddump "
                + "flashcp flash_eraseall i2cget i2cset i2cdetect i2cdump i2ctransfer devmem freeramdisk ipcrm "
                + "raidautorun setkeycodes setconsole wall makedevs mknod setenforce setsebool restorecon load_policy "
                + "fstrim nologin", "System administration", R, Effect.ROOT, Paths.NONE);
        addAll("reboot poweroff halt", "Restart or power off", R, Effect.POWER, Paths.NONE, "-f:force", "-d=:delay",
                "-n:no sync");
        add("logger", "Write to the system log", D, INFO, Paths.NONE, "-t=:tag", "-p=:priority", "-s:also to stderr");
        // ---------------------------------------------------------------- running other programs
        addAll("sh ash hush bash mksh dash zsh xargs nohup nice ionice timeout time watch setsid chroot chpst envdir "
                + "envuidgid setuidgid softlimit runsv runsvdir sv svlogd run-parts start-stop-daemon taskset chrt flock "
                + "script setpriv setarch linux32 linux64 nsenter unshare busybox toybox toolbox su sudo run-as strace "
                + "logwrapper runcon app_process app_process32 app_process64 dalvikvm dalvikvm64 linker linker64 perl "
                + "python python3 lua cttyhack", "Runs other programs", L, Effect.LAUNCH, Paths.NONE);
    }

    private Tools() {
    }

    /** The tool [argv0] names, or null. A path or {@code toybox}/{@code busybox} prefix names the applet behind it. */
    public static Tool get(String argv0) {
        return TOOLS.get(argv0.substring(argv0.lastIndexOf('/') + 1));
    }

    public static int count() {
        return TOOLS.size();
    }

    // ---------------------------------------------------------------- parsing

    /** A command's options and the paths it acts on. */
    static final class Parsed {
        final List<String> flags = new ArrayList<>();
        /** The value each of [flags] was given, or null. */
        final List<String> values = new ArrayList<>();
        final List<String> unknownFlags = new ArrayList<>();
        final List<String> readPaths = new ArrayList<>();
        final List<String> writePaths = new ArrayList<>();
        boolean writes;
        String launches;
    }

    static Parsed parse(Tool t, List<String> argv) {
        Parsed p = new Parsed();
        if (t.effect == Effect.WRITE) p.writes = true;
        List<String> operands = new ArrayList<>();
        boolean optionsEnded = false;
        boolean expression = false; // find: options after the paths
        for (int i = 1; i < argv.size(); i++) {
            String a = argv.get(i);
            if (t.paths == Paths.DD) {
                if (a.startsWith("if=")) p.readPaths.add(a.substring(3));
                if (a.startsWith("of=")) p.writePaths.add(a.substring(3));
                continue;
            }
            if (t.paths == Paths.FIND && !expression && (a.startsWith("-") || a.equals("(") || a.equals("!"))) {
                expression = true;
            }
            if (optionsEnded || !a.startsWith("-") || a.equals("-")) {
                if (expression) continue; // find: expression values were consumed below
                if (!a.equals("-")) operands.add(a);
                continue;
            }
            if (a.equals("--")) {
                optionsEnded = true;
                continue;
            }
            String name = a.contains("=") && a.startsWith("--") ? a.substring(0, a.indexOf('=')) : a;
            Option o = t.options.get(name);
            if (o != null) {
                i = take(p, a, name, o, argv, i, name.length() < a.length() ? a.substring(name.length() + 1) : null);
                continue;
            }
            if (a.startsWith("--") || t.paths == Paths.FIND || a.length() == 2) {
                p.unknownFlags.add(a);
                continue;
            }
            // a cluster of short options: -la, -n5
            for (int k = 1; k < a.length(); k++) {
                String f = "-" + a.charAt(k);
                Option co = t.options.get(f);
                if (co == null) {
                    p.unknownFlags.add(f);
                    continue;
                }
                if (co.takesValue) {
                    String attached = k + 1 < a.length() ? a.substring(k + 1) : null;
                    i = take(p, f, f, co, argv, i, attached);
                    break;
                }
                take(p, f, f, co, argv, i, null);
            }
        }
        // Operands that are not paths: the pattern or script, an owner/mode
        int skip = (t.paths == Paths.AFTER_FIRST && !p.flags.contains("-e") && !p.flags.contains("-f")) ? 1 : 0;
        if (t.paths == Paths.AFTER_FIRST && (t.name.startsWith("ch"))) skip = 1;
        for (int k = skip; k < operands.size(); k++) {
            if (t.paths == Paths.NONE) continue;
            if (p.writes) p.writePaths.add(operands.get(k));
            else p.readPaths.add(operands.get(k));
        }
        // cp/mv/ln/install: only the last operand is written
        if (t.effect == Effect.WRITE && operands.size() > 1 && (t.name.equals("cp") || t.name.equals("mv")
                || t.name.equals("ln") || t.name.equals("install"))) {
            p.writePaths.clear();
            p.readPaths.addAll(operands.subList(0, operands.size() - 1));
            p.writePaths.add(operands.get(operands.size() - 1));
        }
        return p;
    }

    private static int take(Parsed p, String shown, String name, Option o, List<String> argv, int i, String attached) {
        p.flags.add(name);
        if (o.writes) p.writes = true;
        if (o.launches) p.launches = name;
        if (!o.takesValue) {
            p.values.add(null);
            return i;
        }
        String value = attached;
        if (value == null && i + 1 < argv.size()) value = argv.get(++i);
        p.values.add(value);
        if (value != null) {
            if (o.writesValue) p.writePaths.add(value);
            if (o.readsValue) p.readPaths.add(value);
        }
        return i;
    }

    // ---------------------------------------------------------------- where

    /** Locations any app may read too; everything else (the shell's own folders, /proc/net, /data...) is privileged. */
    private static final List<String> ORDINARY = Collections.unmodifiableList(Arrays.asList(
            "/system", "/system_ext", "/product", "/vendor", "/odm", "/apex", "/etc", "/bin", "/sdcard",
            "/storage/emulated/0", "/storage/self/primary", "/proc/self", "/proc/cpuinfo", "/proc/meminfo",
            "/proc/version", "/proc/uptime", "/proc/loadavg", "/proc/filesystems", "/dev/null", "/dev/zero",
            "/dev/random", "/dev/urandom", "/dev/stdin", "/dev/stdout", "/dev/stderr"));

    /** True when [path] is somewhere only the shell (not an ordinary app) can reach. Unclear paths count as privileged. */
    static boolean privilegedPath(String path) {
        if (path.isEmpty() || !path.startsWith("/") || path.contains("/../") || path.endsWith("/..")) return true;
        String p = path.replaceAll("/+", "/");
        if (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        for (String o : ORDINARY) {
            if (p.equals(o) || p.startsWith(o + "/")) return false;
        }
        return true;
    }

    /**
     * Whether the command acts on privileged locations: any path it reads or writes, or (for tools that list folders)
     * the current folder when no path is given.
     */
    public static boolean privileged(List<String> argv) {
        Tool t = get(argv.get(0));
        if (t == null) return false;
        Parsed p = parse(t, argv);
        if (!t.isFileTool() && p.readPaths.isEmpty() && p.writePaths.isEmpty()) return false;
        if (p.readPaths.isEmpty() && p.writePaths.isEmpty()) {
            return t.name.equals("ls") || t.name.equals("find") || t.name.equals("du") || t.name.equals("tree");
        }
        for (String s : p.readPaths) if (privilegedPath(s)) return true;
        for (String s : p.writePaths) if (privilegedPath(s)) return true;
        return false;
    }

    // ---------------------------------------------------------------- what it means

    /** "long format, all entries" for {@code ls -la}; unknown options are named as such. Empty if nothing to say. */
    public static String explain(List<String> argv) {
        Tool t = get(argv.get(0));
        if (t == null) return "";
        Parsed p = parse(t, argv);
        List<String> parts = new ArrayList<>();
        for (int k = 0; k < p.flags.size(); k++) {
            Option o = t.options.get(p.flags.get(k));
            String value = p.values.get(k);
            String part = o == null ? null : value != null ? o.description + ": " + value : o.description;
            if (part != null && !parts.contains(part)) parts.add(part);
        }
        if (!p.unknownFlags.isEmpty()) parts.add("options the broker does not know: " + String.join(" ", p.unknownFlags));
        return String.join(", ", parts);
    }

    /** What the tool and its options can do beyond the ordinary, as a tier, or null for the usual per-app rules. */
    static Classifier.Decision classify(List<String> argv) {
        Tool t = get(argv.get(0));
        if (t == null) return null;
        Parsed p = parse(t, argv);
        if (p.launches != null) {
            return Classifier.Decision.never(t.name + " " + p.launches + " runs other programs");
        }
        switch (t.effect) {
            case LAUNCH:
                return Classifier.Decision.never(t.name + " runs other programs or code");
            case SERVER:
                return Classifier.Decision.never(t.name + " would run a network server as the shell");
            case ROOT:
                return Classifier.Decision.never(t.name + " needs root (" + t.title.toLowerCase() + ")");
            case POWER:
                return Classifier.Decision.gated(Classifier.Gate.SYSTEM, t.name + " (restarts or powers off the phone)");
            case CONTROL:
                return Classifier.Decision.gated(Classifier.Gate.SYSTEM,
                        t.name + " (stops processes of the shell user, which include the broker and adb)");
            case PRIVACY:
                if (t.name.equals("dmesg")) {
                    return Classifier.Decision.ask("dmesg", "read the kernel log, which can contain other apps' activity");
                }
                if (t.category == Category.NETWORK) {
                    return Classifier.Decision.ask("connections", "see every app's network connections");
                }
                return Classifier.Decision.ask("processes", "see which apps and processes are running");
            default:
                break;
        }
        if (t.name.equals("nc") && (p.flags.contains("-l") || p.flags.contains("-L"))) {
            return Classifier.Decision.never("nc -l would run a network server as the shell");
        }
        if (t.name.equals("ip") && argv.size() > 2) {
            for (String a : argv) {
                if (IP_CHANGES.contains(a)) return Classifier.Decision.never("ip " + a + " changes network configuration (needs root)");
            }
        }
        if (t.name.equals("date") && p.flags.contains("-s")) return Classifier.Decision.never("date -s needs root");
        return null;
    }

    private static final java.util.Set<String> IP_CHANGES = new java.util.HashSet<>(Arrays.asList(
            "add", "del", "delete", "change", "replace", "set", "flush", "append", "prepend", "test"));
}
