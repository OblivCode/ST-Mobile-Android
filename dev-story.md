# ST Mobile: development story

## The runtime

### The goal

SillyTavern is a Node.js web app you'd normally run on a computer. ST Mobile's goal is to run it
entirely and minimally on a phone: one APK, no laptop, no Termux, no setup.

But getting there meant answering one question first: how does an Android app run a Node runtime at
all? I looked at two answers: borrowing Termux's environment or finding a way to embed a Node runtime
so that I could run the server directly.

### Initial approach with Termux

Termux came first because it was the first practical answer for me. It's a proven Linux environment on Android. And it's where people already run a lot of this stuff, SillyTavern included. So if my question was "can
Android run Node at all?", Termux already answered it.

The catch is that Termux isn't just a runtime you drop into an app, it's an entire Linux environment you bring in. You get a package manager and all the bits and bobs that Linux carries along. The thought of having a Linux distribution running felt clunky and heavy for a minimal mobile app.

### Research into embedding node

Before committing to Termux, I did some further research into embedding node. It was really a side-by-side of the two routes rather than a hunch. The
questions were the ones that decide these things:
- what does each route actually ship, and how big would the APK end up?
- how much of it comes pre-done, so I'm not reinventing the wheel?
- what Node runtime does each give me? SillyTavern needs a modern one.
- how much room does each leave me later?

The tiebreaker landed on a single decision. Termux gives me an entire environment, and embedding gives me a simple runtime. The choice was pretty clear to me. So the next step was to research how to actually embed a Node runtime.

### How people actually embed Node

So Node normally ships as a program, obviously. But I had to put it inside an Android app, so I needed it as a library instead. The next step in my long night of research was digging into how people had actually embedded Node on mobile. That led me to the GitHub project nodejs-mobile, which builds Node into `libnode.so`.

The immediate pitfall, the thing that almost killed the golden route, was that nodejs-mobile went quiet about two years ago. And SillyTavern needs a recent Node version (Node 20 or newer).

But fortunately the project wasn't dead. The community carried it on, and the forks keep it current, Node 24. I didn't want to build and maintain my own Node for Android, so I picked one of those builds and pinned it. If it ever stalls, swapping it is a small change.

### Making the runtime run

I had a Node library now, `libnode.so`. And now I needed a way to run it.

At this point the term `.so` meant nothing to me, though I did know the concept under a different name: DLL. Same idea, Linux calls it a shared object: compiled code that other programs load when they need it, rather than a program you launch yourself. So I had the library, and a library can't be launched. I had to find something to run it.

My first thought was that since it is a library, I just need to call some sort of "main" function. Close, but not quite. Node does expose an entry point, `node::Start`. What I had not worked out was where the thing calling it would live.

My instinct was my own app. I was writing Kotlin, so I assumed the Kotlin side would call into the library somehow. A little more research followed, and I came across *option one*: JNI.

JNI, it turns out, is just a bridge between Kotlin and C or C++. It's the same idea I already knew from C#, where it's called P/Invoke: one side says "call this native function," the other side answers. So option one had a shape now. Load `libnode.so` into my app, call Node's entry point across that bridge, and Node runs.

With things taking shape, I wanted to double-check the point I was at. I've already been burned once on this project by moving too fast, so before I wrote a line I wanted to know two things: what "working" would actually mean, and whether this was even sane.

For that I sought the aid of an AI agent, the first time I'd done it on a serious project.

So I had the agent review my solution and give me a second opinion. It did a lot more than that. First, it suggested I let it clone and audit the SillyTavern project itself, and there it unearthed a lot of edge cases I had not covered.

### The SillyTavern edge cases (optional)

So the agent cloned the exact SillyTavern commit I shipped and went through the whole thing. The audit turned up a few edge cases I hadn't gotten to at this point in development.

- **It exits constantly.** `process.exit()` is Node's way of quitting, and SillyTavern calls it in normal paths: the shutdown handler, the crash handler, a pile of startup failures. Harmless when Node is its own process. Not so much as a thread inside my app (takes the whole thing down).
- **It owns its signal handlers.** `SIGINT` and `SIGTERM` are the signals a system sends a program to ask it to stop, and both are wired to `process.exit()`. In-process, there's no process to signal.
- **It leans on `process.cwd()`.** Config paths, the address whitelist, user data, all built from the working directory. As its own program, that's fine, since it works in its own directory. As a thread, you'd be changing the whole app's, which takes an extra layer of careful management.

### Amending the edge case (optional)

The audit cut both ways. SillyTavern's own code never spawns a process. No `child_process`, no `fork`, no `spawn`, no `process.execPath`. So the objection I'd assumed would sink option one, "Node won't be a real process anymore," didn't apply at all. That's a big part of why it looked so close.

Before writing option one off, I asked the obvious question: if the only thing in the way was `process.exit()`, couldn't I just intercept it? Turns out I could, and it's a supported feature, not a hack. `process.exit()` doesn't end the program itself; it goes through a handler Node lets you replace, and you drop in one that stops the runtime instead of the app.

That took care of the exits and the signal handlers at once. The constant quitting stopped being fatal, and the signals came along for free, because `SIGINT` and `SIGTERM` are both wired to `process.exit()` and end up in the same place. There was one wrinkle: with no separate process, there's no signal to send, so I'd have to trigger that shutdown path myself instead. And it did nothing for `process.cwd()`, which was still global and had nothing to do with quitting anyway.

Then I hit the catches it couldn't reach at all.

- **A crash never goes through the handler.** A memory fault, a V8 out-of-memory (V8 is Node's JavaScript engine), a failed internal check, those don't stop at a handler, they just end the process. And if that process is my app, they end my app. On a phone, running out of memory is not a rare event.
- **Using the API has a price.** It's meant for people embedding Node by hand, so reaching for it meant giving up the simple path and owning the whole embedder lifecycle myself: starting the runtime, managing its threads, stopping it cleanly. And keeping all of it in step with every Node upgrade.

So option one wasn't fatal. It was survivable, at a cost: a crash no longer stayed contained, and I'd carry extra code forever and move the working directory for the whole app, all for a difference no user would ever see.

### Option two: give it its own program to launch from

Instead of pulling Node into my app, the solution was to give it its own process, through a launcher. A launcher, in this context, just means a small program whose only job is to start Node. It links `libnode.so` and calls `node::Start()`.

I had never written C++, so "link" wasn't a familiar term. From what I learned, linking is the step that wires a program to the libraries it uses, and it happens when the program is built. The nearest thing I knew was adding a reference in C#.

Then I hit a distinction I had never had to think about. Libraries come in two kinds. A static one gets copied into your program at build time, so the program carries it. A shared one doesn't: the program just records that it needs the library, and the code is loaded from a separate file when it starts. `libnode.so` is the shared kind, which is why the launcher doesn't contain Node itself, only points at it.

So a little program that starts Node. On Android, though, a program can't live just anywhere. Android won't let an app run one out of its own storage. The rule is there so an app can't quietly drop a program into its own folder and launch it, a reasonable security choice. What it *will* run is the shared libraries that ship inside the app. When you install the app, Android unpacks those libraries into a folder the app itself can't write to, and marks them as runnable. So if the launcher ships as a library too, Android puts it in that safe folder, already runnable, and the app runs it from there. Node ends up a real process, sitting beside mine instead of inside it.

---

<!-- ===================================================================== -->
<!-- DRAFT / UNREVIEWED SECTION — raw material, deliberately overwritten.   -->
<!-- Written with heavy AI assistance. Likely slots in just after           -->
<!-- "How people actually embed Node" and before "Making the runtime run".  -->
<!-- Not part of the reviewed narrative yet. Cut this down before shipping. -->
<!-- ===================================================================== -->

## Inside embedding, there were two ways to run it (draft — unreviewed)

I have to be honest about how I got to the launcher, because there was a fork in the road
and I nearly walked down the other side.

I'm a Python and C# developer. Android internals, the NDK, JNI, the whole native side of
this — that's outside the work I do every day. So when it came time to actually run
`libnode.so`, I did the two things available to me: I read a great deal, and I leaned on
an AI agent seriously for the first time in a real project. Not to write the whole app,
but as a research partner — something that could hold both sides of a decision I didn't
feel qualified to make on my own.

This section is the result of that, which is exactly why it's marked unreviewed.

### Two doors

Once I had `libnode.so`, there were two ways to run it, and they lead to very different
places.

**Door one: give it a companion.** Node is normally a program, so hand it a program's
life — a tiny executable that links `libnode.so` and calls `node::Start()`. A real
process again, with its own memory and its own fate.

**Door two: pull it inside.** Don't spawn anything. Load `libnode.so` into the app with
JNI and call `node::Start()` on a thread. Node runs *inside* the app process, as one more
thread.

If you've embedded CPython with `Py_Initialize()` instead of shelling out to `python.exe`,
you already know the difference: door one is `python.exe`, door two is embedding the
interpreter in your own process.

### The first attempt outran me

An AI agent had already moved on one of these before I understood it well enough to judge
it, and I pulled it back. `[SLOT: what it built, what you saw, why you reverted]`. I
couldn't even tell you now whether it worked — not because it failed, but because we'd
never agreed what "working" would mean for it. That's the part that stuck with me. An
implementation you can't grade isn't progress; it's a coin flip with extra steps.

So for the real decision, I did it the other way round: get the analysis first, and let it
tell me whether building anything was even worth it.

### So I put the agent on the comparison

I asked the AI to go away and argue both doors properly, using my actual situation as the
test rather than generic advice. It cloned the exact SillyTavern commit I ship and went
through it, and what came back was more useful than a feature list.

**Does SillyTavern need a real process at all?** That was my main worry about door two.
The answer, surprisingly, was no — ST's own code never once spawns a process. No
`child_process`, no `fork`, no `spawn`, no `execPath`. A whole class of "but it isn't a
real process anymore" problems simply didn't apply.

**What happens when ST quits?** This was the real one. ST calls `process.exit()` all over
the place — its shutdown handler, its crash handler, its startup failures. As a real
process, that's harmless: the child goes down and gets restarted. As a thread inside the
app, it would take the whole app with it. That looked fatal for door two, until the agent
found the escape hatch: Node exposes a supported way to intercept it,
`SetProcessExitHandler`, so `process.exit()` can stop the runtime instead of the app.

`[SLOT: is it fair to say the AI found this, in your own words? Or "we found".]`

**And then the catch that decided it.** Intercepting `process.exit()` is `sys.exit()` —
catchable. But a segfault or a V8 out-of-memory is `os._exit()`: it doesn't go through
anything, it just ends the process. If Node *is* the app process, that's the app. On a
phone, "V8 ran out of memory" is not a rare event.

### The comparison

How the two doors actually run:

| | Launcher (door one) | In-process (door two) |
| :--- | :--- | :--- |
| Node runs as | its own process | a thread inside the app |
| `process.exit()` | exits the child only | interceptable via `SetProcessExitHandler` |
| Hard crash / OOM / segfault | child dies, app survives, restart | **app + WebView die together** |
| Graceful stop | send SIGTERM to the child | intercept and stop by hand |
| `process.cwd()` | set it for the child, done | change it for the whole app |
| `process.pid` | real Node id | the app's id |
| Debugging | `ps`, kill, real log files | none of that |
| Crash recovery | free | none for hard crashes |

What each one costs:

| | Launcher (door one) | In-process (door two) |
| :--- | :--- | :--- |
| Code I own | ten lines + CMake | the entire embedder lifecycle |
| Packaging trick | needs `useLegacyPackaging` for the exec bit | not needed |
| Disk | APK + ~82 MB extracted libraries | libraries stay in the APK |
| Functional gain | — | **none** |
| Fits "minimal and robust" | yes | not really |

### Where I landed

I picked the launcher.

Door two's only wins were packaging — no executable, no extraction, a slightly smaller and
faster install. Everything else was a cost, and because SillyTavern doesn't spawn
processes, there wasn't even a capability on the other side of that cost.

And I picked it on purpose, after understanding the alternative, instead of by default. I
could tell you what door two is, why someone would walk through it, exactly what it would
cost me, and the one thing (`useLegacyPackaging`) that would make me reconsider. That's
the difference between a choice and a guess, and it's the difference the first fast
attempt taught me to care about.

The launcher is boring. Boring was the point.

## SillyTavern itself

<!-- TODO: Getting SillyTavern onto the phone; The launch contract; The data story; The traps -->

## The app around it

<!-- TODO: Keeping it alive; The shell; Security -->

## Shipping it

<!-- TODO: Reproducible builds; Where it stood -->

## Licensing
