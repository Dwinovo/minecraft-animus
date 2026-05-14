# NOTICE

This project incorporates work that was authored by the same person under a
different upstream project. The notices below document the provenance and the
re-licensing terms that apply to those portions.

## Migrated animation engine

The Bedrock animation pipeline under
`common/src/main/java/com/dwinovo/animus/anim/` was migrated from the author's
own [`minecraft-chiikawa`](https://github.com/dwinovo/minecraft-chiikawa) project.
The upstream project is licensed under **CC BY-NC-SA 4.0**.

As the sole copyright holder of `minecraft-chiikawa`, **dwinovo has
re-licensed the migrated source code under the
[PolyForm Noncommercial 1.0.0](LICENSE)** for use in this project. CC BY-NC-SA's
ShareAlike clause binds downstream redistributors, not the original author;
the author may release their own work under additional terms.

Files affected:

- All Java sources under `common/src/main/java/com/dwinovo/animus/anim/`
- Bedrock-coord-system mirroring logic in `ModelBaker.java`
- Mini-Molang AST and evaluator under `anim/molang/`
- Pose sampler / mixer / controller framework

The migration removed the pet-specific semantics layer (`PetActivity`,
`PetAnimationResolver`, `AbstractPet`, etc.) and the held-item render layer.
The remaining engine is single-entity-agnostic and waits on the upcoming
LLM-driven gameplay layer for state input.

## Default Hachiware art assets

The default model and texture for the Animus entity are stored at:

- `common/src/main/resources/assets/animus/models/entity/hachiware.json`
- `common/src/main/resources/assets/animus/animations/hachiware.json`
- `common/src/main/resources/assets/animus/textures/entities/hachiware.png`

These art assets are **dwinovo's original creation** (built in Blockbench
from scratch, not derived from third-party reference sheets). The upstream
`minecraft-chiikawa` distribution licensed them under CC BY-NC-SA 4.0; as the
sole copyright holder, dwinovo has **re-licensed these assets under
[CC BY-NC 4.0](LICENSE-ART)** for use in this project.

## Third-party content

This project does not currently include any third-party-authored assets or
code. If/when third-party content is added (e.g. CC0 textures, community
contributions), it will be tracked in a separate `THIRD_PARTY_NOTICES.md`
with the original source and license.
