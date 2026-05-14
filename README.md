# Animus (built on the MultiLoader Template)

> **License (source-available, non-commercial)**
>
> - **Code** is licensed under **[PolyForm Noncommercial 1.0.0](LICENSE)** — you may use, modify, and redistribute the source for any noncommercial purpose, with attribution.
> - **Art assets** (entity models, textures, sounds, etc. — anything under `assets/` that is not source code) are licensed under **[CC BY-NC 4.0](LICENSE-ART)** — you may use and create derivative works for noncommercial purposes, with attribution.
> - **Commercial use of either requires a separate license from the author.**
>
> This is *source-available*, not OSI-approved "open source" (the OSI definition forbids non-commercial restrictions). Please use the term "source-available" when describing this project.

---

# MultiLoader Template

This project provides a Gradle project template that can compile Minecraft mods for multiple modloaders using a common project for the sources. This project does not require any third party libraries or dependencies. If you have any questions or want to discuss the project, please join our [Discord](https://discord.myceliummod.network).

## Getting Started

### IntelliJ IDEA
This guide will show how to import the MultiLoader Template into IntelliJ IDEA. The setup process is roughly equivalent to setting up the modloaders independently and should be very familiar to anyone who has worked with their MDKs.

1. Clone or download this repository to your computer.
2. Configure the project by setting the properties in the `gradle.properties` file. You will also need to change the `rootProject.name`  property in `settings.gradle`, this should match the folder name of your project, or else IDEA may complain.
3. Open the template's root folder as a new project in IDEA. This is the folder that contains this README.md file and the gradlew executable.
4. If your default JVM/JDK is not Java 25 you will encounter an error when opening the project. This error is fixed by going to `File > Settings > Build, Execution, Deployment > Build Tools > Gradle > Gradle JVM` and changing the value to a valid Java 25 JVM. You will also need to set the Project SDK to Java 25. This can be done by going to `File > Project Structure > Project SDK`. Once both have been set open the Gradle tab in IDEA and click the refresh button to reload the project.
5. Open your Run/Debug Configurations. Under the `Application` category there should now be options to run Fabric and NeoForge projects. Select one of the client options and try to run it.
6. Assuming you were able to run the game in step 5 your workspace should now be set up.

### Eclipse
While it is possible to use this template in Eclipse it is not recommended. During the development of this template multiple critical bugs and quirks related to Eclipse were found at nearly every level of the required build tools. While we continue to work with these tools to report and resolve issues support for projects like these are not there yet. For now Eclipse is considered unsupported by this project. The development cycle for build tools is notoriously slow so there are no ETAs available.

## Development Guide
When using this template the majority of your mod should be developed in the `common` project. The `common` project is compiled against the vanilla game and is used to hold code that is shared between the different loader-specific versions of your mod. The `common` project has no knowledge or access to ModLoader specific code, apis, or concepts. Code that requires something from a specific loader must be done through the project that is specific to that loader, such as the `fabric` or `neoforge` projects.

Loader specific projects such as the `fabric` and `neoforge` project are used to load the `common` project into the game. These projects also define code that is specific to that loader. Loader specific projects can access all the code in the `common` project. It is important to remember that the `common` project can not access code from loader specific projects.

## Removing Platforms and Loaders
While this template has support for many modloaders, new loaders may appear in the future, and existing loaders may become less relevant.

Removing loader specific projects is as easy as deleting the folder, and removing the `include("projectname")` line from the `settings.gradle` file.
For example if you wanted to remove support for `forge` you would follow the following steps:

1. Delete the subproject folder. For example, delete `MultiLoader-Template/forge`.
2. Remove the project from `settings.gradle`. For example, remove `include("forge")`. 
