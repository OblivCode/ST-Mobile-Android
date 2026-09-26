# ST Mobile: development story

## The goal

SillyTavern is a Node.js web app you'd normally run on a computer. ST Mobile's goal is to run it
entirely and minimally on a phone: one APK, no laptop, no Termux, no setup.

But getting there meant answering one question first: how does an Android app run a Node runtime at
all? I looked at two answers: borrowing Termux's environment or finding a way to embed a Node runtime
so that I could run the server directly.

## Initial approach with Termux

Termux came first because it was the first practical answer for me. It's a proven Linux environment on Android. And it's where people already run a lot of this stuff, SillyTavern included. So if my question was "can
Android run Node at all?", Termux already answered it.

The catch is that Termux isn't just a runtime you drop into an app, it's an entire Linux environment you bring in. You get a package manager and all the bits and bobs that Linux carries along. The thought of having a Linux distribution running felt clunky and heavy for a minimal mobile app.

## Research into embedding node

Before committing to Termux, I did some further research into embedding node. It was really a side-by-side of the two routes rather than a hunch. The
questions were the ones that decide these things:
- what does each route actually ship, and how big would the APK end up?
- how much of it comes pre-done, so I'm not reinventing the wheel?
- what Node runtime does each give me? SillyTavern needs a modern one.
- how much room does each leave me later?

The tiebreaker landed on a single decision. Termux gives me an entire environment, and embedding gives me a simple runtime. The choice was pretty clear to me. So the next step was to research how to actually embed a Node runtime.

## How people actually embed Node

So Node normally ships as a program, obviously. But I had to put it inside an Android app, so I needed it as a library instead. The next step in my long night of research was digging into how people had actually embedded Node on mobile. That led me to the GitHub project nodejs-mobile, which builds Node into `libnode.so`.

The immediate pitfall, the thing that almost killed the golden route, was that nodejs-mobile went quiet about two years ago. And SillyTavern needs a recent Node version (Node 20 or newer).

But fortunately the project wasn't dead. The community carried it on, and the forks keep it current, Node 24. I didn't want to build and maintain my own Node for Android, so I picked one of those builds and pinned it. If it ever stalls, swapping it is a small change.

## Making the runtime run

I had a Node library now, `libnode.so`.

Modern Android forbids an app from running a program out of its own storage, which is exactly where an unpacked or downloaded binary would land. The rule is there so apps can't quietly drop executables and launch them, a reasonable security choice.

But there is one place Android allows it, and that is where it keeps an app's native libraries. That folder is read-only to the app and managed by the system, so it is trusted. And Android puts the libraries from the APK there for you. So the move was to give the runtime a companion: a tiny launcher, shipped the same way as a native library. Android would extract it into that trusted folder, executable and all. The launcher links `libnode.so` and starts it.
