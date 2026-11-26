# Evangelium server

The server software that runs inside [Project: Evangelium](https://evangelium.cc): a multithreaded Minecraft server. Several threads
run one world together (each region of chunks is ticked by one thread at a time, and a region can split its work over helper threads),
with cheaper crowds and mob farms, a region tick budget, faster joins, chunk generation and chunk saving.

How we measure it: https://evangelium.cc/benchmarks · Questions: support@evangelium.cc

- [HOW_IT_WORKS.md](HOW_IT_WORKS.md): how the threading works
- [DEVELOPING_A_MULTITHREAD_PLUGIN.md](DEVELOPING_A_MULTITHREAD_PLUGIN.md): writing plugins that are safe on several threads
- [EVANGELIUMSERVER_YAML.md](EVANGELIUMSERVER_YAML.md): the settings in evangelium.yml

## Building

1. `./gradlew applyAllPatches`
2. `./gradlew createMojmapPaperclipJar` (the jar ends up in `evangeliumserver-server/build/libs`)

## License

All code is licensed under [GPLv3](LICENSE.txt). Based on Paper, Purpur and ShreddedPaper, whose authors keep their copyright.
