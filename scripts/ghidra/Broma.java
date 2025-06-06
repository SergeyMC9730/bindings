
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Broma {
    static int PLACEHOLDER_ADDR = 0x9999999;

    public class Range {
        public final int start;
        public final int end;
        Range() {
            this.start = 0;
            this.end = 0;
        }
        Range(int where) {
            this.start = where;
            this.end = where;
        }
        Range(int start, int end) {
            this.start = start;
            this.end = end;
        }
        boolean overlaps(Range other) {
            return start < other.end && other.start < end;
        }
        Range join(Range other) {
            return new Range(Math.min(this.start, other.start), Math.max(this.end, other.end));
        }
    }

    /**
     * A match in the source Broma string, parsed from Regex. The start and end of 
     * the match are in relation to the full original Broma file, not whatever Regex 
     * matched them
     */
    public class Match {
        public final Range range;
        public final String value;

        private Match(Range r, String v) {
            range = r;
            value = v;
        }
        private Match(String v) {
            range = new Range();
            value = v;
        }
        private Match(Matcher matcher, String group) {
            range = new Range(matcher.start(group), matcher.end(group));
            value = matcher.group(group);
        }
        private static Optional<Match> maybe(Broma broma, Matcher matcher, String group) {
            try {
                if (matcher.group(group) == null) {
                    return Optional.empty();
                }
                return Optional.of(broma.new Match(matcher, group));
            }
            catch(IllegalStateException exception) {
                return Optional.empty();
            }
        }
    }

    /**
     * A replacement string to be applied to the source Broma string
     */
    private class Patch {
        public final Range range;
        public final String patch;

        private Patch(Range range, String patch) {
            this.range = range;
            this.patch = patch;
        }
    }

    public abstract class Parseable {
        public final Range range;
        public final String raw;
        public final Broma broma;

        Parseable(int ignore) {
            this.range = new Range();
            this.broma = null;
            this.raw = null;
        }
        Parseable(Broma broma, Matcher matcher) {
            this.broma = broma;
            this.range = new Range(matcher.start(), matcher.end());
            this.raw = matcher.group();
        }
        public String toString() {
            if (broma == null) {
                return "<generated by SyncBromaScript - this should not be visible!>";
            }
            return broma.data.substring(range.start, range.end);
        }
    }

    public class Type extends Parseable {
        public final Match name;
        public final Optional<Match> template;
        public final boolean unsigned;
        public final Optional<Match> ptr;
        public final Optional<Match> ref;

        private Type(String name, String template, boolean pointer, boolean unsigned) {
            super(0);
            this.name = new Match(name);
            this.template = template.isEmpty() ? Optional.empty() : Optional.of(new Match(template));
            this.unsigned = unsigned;
            this.ptr = pointer ? Optional.of(new Match("*")) : Optional.empty();
            this.ref = Optional.empty();
        }

        /**
         * Make a pointer type (for passing <code>this</code> args)
         * @param name
         */
        public static Type ptr(Broma broma, String name) {
            return broma.new Type(name, "", true, false);
        }

        private Type(Broma broma, Platform platform, Matcher matcher) {
            super(broma, matcher);
            name = broma.new Match(matcher, "name");
            template = Match.maybe(broma, matcher, "template");
            unsigned = matcher.group("sign") != null && matcher.group("sign").equals("unsigned");
            ptr = Match.maybe(broma, matcher, "ptr");
            ref = Match.maybe(broma, matcher, "ref");
        }
    }

    public class Param extends Parseable {
        public final Type type;
        public final Optional<Match> name;
        public final Optional<Match> nameInsertionPoint;

        private Param(Broma broma, Platform platform, Matcher matcher) {
            super(broma, matcher);
            Type type = new Type(broma, platform, broma.forkMatcher(Regexes.GRAB_TYPE, matcher, "type", true));
            Optional<Match> name = Match.maybe(broma, matcher, "name");
            if (name.isPresent() && name.get().value.equals("long")) {
                // Special case for long longs in the function signature
                // This is a hack, but it works for now
                type = new Type(type.name.value + "long", "", false, type.unsigned);
                name = Optional.empty();
            }
            this.type = type;
            this.name = name;
            nameInsertionPoint = Match.maybe(broma, matcher, "insertnamehere");
        }
    }

    public class Function extends Parseable {
        public final Class parent;
        public final Optional<Match> dispatch;
        public final Optional<Type> returnType;
        public final Optional<Match> name;
        public final Optional<Match> destructor;
        public final List<Param> params;
        public final Optional<Match> platformOffset;
        public final Optional<Range> platformOffsetAddPoint;
        public final Optional<Match> platformOffsetInsertPoint;

        private Function(Broma broma, Class parent, Platform platform, Matcher matcher) {
            super(broma, matcher);
            this.parent = parent;

            dispatch = Match.maybe(broma, matcher, "dispatch");
            if (matcher.group("return") != null) {
                returnType = Optional.of(
                    new Type(broma, platform, broma.forkMatcher(Regexes.GRAB_TYPE, matcher, "return", true))
                );
            }
            else {
                returnType = Optional.empty();
            }
            name = Match.maybe(broma, matcher, "name");
            destructor = Match.maybe(broma, matcher, "destructor");
            platformOffsetInsertPoint = Match.maybe(broma, matcher, "insertplatformshere");
            params = new ArrayList<Param>();

            // Match parameters
            var paramMatcher = broma.forkMatcher(Regexes.GRAB_PARAM, matcher, "params", false);
            while (paramMatcher.find()) {
                params.add(broma.new Param(broma, platform, paramMatcher));
            }

            if (matcher.group("platforms") != null) {
                platformOffset = Match.maybe(
                    broma,
                    broma.forkMatcher(Regexes.grabPlatformAddress(platform), matcher, "platforms", true),
                    "addr"
                );
                if (platformOffset.isEmpty()) {
                    platformOffsetAddPoint = Optional.of(new Range(matcher.end("platforms")));
                }
                else {
                    platformOffsetAddPoint = Optional.empty();
                }
            }
            else {
                platformOffset = Optional.empty();
                platformOffsetAddPoint = Optional.empty();
            }
        }

        public String getName() {
            return this.name.orElseGet(() -> this.destructor.get()).value;
        }
        
        public CConv getCallingConvention(Platform platform) {
            if (platform != Platform.WINDOWS32) {
                if (parent != null && (dispatch.isEmpty() || !dispatch.get().value.contains("static"))) return CConv.THISCALL;

                switch (platform) {
                    case WINDOWS64:
                        return CConv.FASTCALL;
                    case MAC_INTEL:
                        return CConv.STDCALL;
                    case ANDROID32:
                    case ANDROID64:
                    case MAC_ARM:
                    case IOS:
                        return CConv.CDECL;
                    default:
                        return CConv.DEFAULT;
                }
            }
            if (dispatch.isPresent()) {
                if (dispatch.get().value.contains("virtual")) {
                    return CConv.THISCALL;
                }
                if (dispatch.get().value.contains("callback")) {
                    return CConv.THISCALL;
                }
                if (dispatch.get().value.contains("static")) {
                    return CConv.OPTCALL;
                }
            }
            if (parent.linked) {
                return CConv.THISCALL;
            }
            return CConv.MEMBERCALL;
        }
    }
    
    public class Member extends Parseable {
        public final Class parent;
        public final Optional<Match> comments;
        public final Optional<Type> type;
        public final Optional<Match> name;
        public final Map<Platform, Integer> paddings;

        private Member(Broma broma, Class parent, Platform platform, Matcher matcher) {
            super(broma, matcher);
            this.parent = parent;
            this.comments = Match.maybe(broma, matcher, "comments");
            if (matcher.group("name") != null) {
                name = Match.maybe(broma, matcher, "name");
                type = Optional.of(new Type(broma, platform, broma.forkMatcher(Regexes.GRAB_TYPE, matcher, "type", true)));
                paddings = Map.of();
            }
            else {
                name = Optional.empty();
                type = Optional.empty();
                
                var mutPaddings = new HashMap<Platform, Integer>();
                var addrMatcher = broma.forkMatcher(Regexes.GRAB_PLATFORM_ADDRESS, matcher, "platforms", false);
                while (addrMatcher.find()) {
                    mutPaddings.put(
                        Platform.fromShortName(addrMatcher.group("platform"), platform),
                        Integer.parseInt(addrMatcher.group("addr"), 16)
                    );
                }
                paddings = Map.copyOf(mutPaddings);
            }
        }

        private static String removeCommentPrefix(String str) {
            return str.startsWith("//") ? str.substring(2).trim() : str;
        }
        public Optional<String> getComment() {
            if (comments.isPresent()) {
                return Optional.of(String.join(
                    "\n",
                    comments.get().value.lines()
                        .map(line -> removeCommentPrefix(line.trim()))
                        .toList()
                ).trim());
            }
            return Optional.empty();
        }
        public String paddingNamesToString() {
            return "GEODE(" + String.join(
                "|",
                paddings.entrySet().stream().map(e -> e.getKey().getShortName()).toList()
            ) + ")";
        }
        public String paddingsToString() {
            return String.join(
                ", ",
                paddings.entrySet()
                    .stream()
                    .map(e -> e.getKey().getShortName() + " 0x" + Integer.toHexString(e.getValue()))
                    .toList()
            );
        }
    }

    public class Class extends Parseable {
        public final boolean linked;
        public final Match name;
        public final List<Function> functions;
        public final List<Member> members;
        public final Range beforeClosingBrace;
        public final boolean hasBases;

        private Class(Broma broma, Platform platform, Matcher matcher) {
            super(broma, matcher);

            name = new Match(matcher, "name");
            functions = new ArrayList<Function>();
            members = new ArrayList<Member>();
            beforeClosingBrace = new Range(matcher.start("closingbrace"), matcher.start("closingbrace"));
            hasBases = matcher.group("bases") != null;

            // Check if this class is linked
            var attrs = matcher.group("attrs");
            if (attrs != null) {
                var attr = Regexes.GRAB_LINK_ATTR.matcher(attrs);
                if (attr.find() && attr.group("platforms").contains(platform.getShortName())) {
                    linked = true;
                }
                else {
                    linked = false;
                }
            }
            else {
                linked = false;
            }

            // Match functions
            var funMatcher = broma.forkMatcher(Regexes.GRAB_FUNCTION, matcher, "body", false);
            while (funMatcher.find()) {
                functions.add(broma.new Function(broma, this, platform, funMatcher));
            }

            // Match members
            var memMatcher = broma.forkMatcher(Regexes.GRAB_MEMBER, matcher, "body", false);
            while (memMatcher.find()) {
                members.add(broma.new Member(broma, this, platform, memMatcher));
            }
        }

        /**
         * Get matching function overloads by name
         * @param name
         * @return The functions
         */
        public List<Function> getFunctions(String name) {
            return this.functions.stream().filter(i -> i.getName().equals(name)).toList();
        }
    }

    /**
     * Path to the Broma file
     */
    public final Path path;
    /**
     * The Broma file's data as a string
     */
    private final String data;
    /**
     * A list of edits to apply to the Broma file when saving
     */
    private final List<Patch> patches;
    public final List<Class> classes;
    public final List<Function> functions;
    private boolean committed = false;

    private Matcher forkMatcher(Pattern regex, Matcher of, String group, boolean find) {
        var matcher = regex.matcher(this.data);
        matcher.region(of.start(group), of.end(group));
        if (find) {
            matcher.find();
        }
        return matcher;
    }
    private void applyRegexes(Platform platform) {
        var matcher = Regexes.GRAB_CLASS.matcher(this.data);
        while (matcher.find()) {
            this.classes.add(new Class(this, platform, matcher));
        }

        var funMatcher = Regexes.GRAB_GLOBAL_FUNCTION.matcher(this.data);
        while (funMatcher.find()) {
            this.functions.add(new Function(this, null, platform, funMatcher));
        }
    }

    private Broma() {
        this.path = null;
        this.data = null;
        this.patches = null;
        this.classes = null;
        this.functions = null;
    }

    public static Broma fake() {
        return new Broma();
    }

    public static Type fakeType(String name) {
        var unsigned = name.startsWith("unsigned ");
        if (unsigned) {
            name = name.substring(9);
        }
        var pointer = name.endsWith("*");
        if (pointer) {
            name = name.substring(0, name.length() - 1).trim();
        }
        var template = name.contains("<") ? name.substring(name.indexOf("<")) : "";
        if (template.length() > 0) {
            name = name.substring(0, name.indexOf("<")).trim();
        }
        return fake().new Type(name, template, pointer, unsigned);
    }

    /**
     * Open up a new Broma file for reading & editing
     * @param path Path to the Broma file
     * @throws IOException
     */
    public Broma(Path path, Platform platform) throws IOException {
        this.path = path;
        data = Files.readString(path);
        patches = new ArrayList<Patch>();
        classes = new ArrayList<Class>();
        functions = new ArrayList<Function>();
        this.applyRegexes(platform);
    }

    /**
     * Get a class by name
     * @param name
     * @return A reference to the class, or <code>Optional.empty()</code> if none found
     */
    public Optional<Class> getClass(String name) {
        return this.classes.stream().filter(i -> i.name.value.equals(name)).findFirst();
    }

    /**
     * Add a new patch to this Broma file. The patch will be applied when 
     * <code>save</code> is called. <b>It is assumed that no patches overlap!</b>
     * @param range The range of the patch in the Broma file
     * @param patch The string contents of the patch
     */
    public void addPatch(Range range, String patch) {
        this.patches.add(new Patch(range, patch));
    }

    /**
     * Commit this Broma's patches and save the changes to disk
     * @throws IOException
     */
    public void save() throws IOException, Error {
        if (this.committed) {
            throw new Error("Broma file has already been saved");
        }
        this.committed = true;
        this.patches.sort((a, b) -> b.range.end - a.range.end);
        Range prev = null;
        for (var patch : this.patches) {
            if (prev != null) {
                ScriptWrapper.rtassert(
                    !patch.range.overlaps(prev),
                    "There are overlapping patches: {0}..{1} and {2}..{3}",
                    patch.range.start, patch.range.end, 
                    prev.start, prev.end
                );
            }
            prev = patch.range;
        }
        var result = new StringBuffer(this.data);
        for (var patch : this.patches) {
            result.replace(patch.range.start, patch.range.end, patch.patch);
        }
        Files.writeString(this.path, result.toString());
    }
}
