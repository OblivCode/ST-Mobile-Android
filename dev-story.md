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
