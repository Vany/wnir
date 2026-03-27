# GeckoLib + NeoForge Modding Guide (Minecraft 1.21.11)

## Overview

This guide explains how to use GeckoLib with NeoForge to create animated Minecraft mods. It assumes you already understand NeoForge basics and focuses on integrating animation systems.

---

# 1. Mental Model

## Vanilla Minecraft
- Static models
- Animations hardcoded in Java

## GeckoLib
- Models defined in JSON (Blockbench)
- Animations defined in JSON
- Logic handled via Java controllers

---

# 2. Setup

## Add Dependency

```gradle
repositories {
    maven {
        name = "GeckoLib"
        url = "https://dl.cloudsmith.io/public/geckolib3/geckolib/maven/"
    }
}

dependencies {
    implementation "software.bernie.geckolib:geckolib-neoforge-1.21:VERSION"
}
```

## Initialize

```java
import software.bernie.geckolib.GeckoLib;

public class YourMod {
    public YourMod() {
        GeckoLib.initialize();
    }
}
```

---

# 3. Asset Pipeline

## Use Blockbench
- Install GeckoLib plugin
- Create:
  - Model
  - Bones
  - Animations

## Export Files

```
geo/entity.geo.json
animations/entity.animation.json
textures/entity.png
```

## Resource Structure

```
resources/assets/yourmod/
 ├── geo/
 ├── animations/
 └── textures/
```

---

# 4. Animated Entity

## Entity Class

```java
public class MyEntity extends PathfinderMob implements GeoEntity {

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    public MyEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "controller", 0, state -> {
            if (state.isMoving()) {
                return state.setAndContinue(RawAnimation.begin().thenLoop("walk"));
            }
            return state.setAndContinue(RawAnimation.begin().thenLoop("idle"));
        }));
    }
}
```

---

## Model Class

```java
public class MyEntityModel extends GeoModel<MyEntity> {

    @Override
    public ResourceLocation getModelResource(MyEntity animatable) {
        return new ResourceLocation("yourmod", "geo/entity.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(MyEntity animatable) {
        return new ResourceLocation("yourmod", "textures/entity.png");
    }

    @Override
    public ResourceLocation getAnimationResource(MyEntity animatable) {
        return new ResourceLocation("yourmod", "animations/entity.animation.json");
    }
}
```

---

## Renderer

```java
public class MyEntityRenderer extends GeoEntityRenderer<MyEntity> {

    public MyEntityRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new MyEntityModel());
    }
}
```

---

## Register Renderer

```java
EntityRenderers.register(MyEntities.MY_ENTITY.get(), MyEntityRenderer::new);
```

---

# 5. Animation System

## AnimationController
Controls when and what animation plays.

## RawAnimation

```java
RawAnimation.begin().thenLoop("walk");
RawAnimation.begin().thenPlay("attack");
```

## Predicate Example

```java
state -> {
    if (state.isMoving()) {
        return state.setAndContinue(WALK);
    }
    return state.setAndContinue(IDLE);
}
```

---

# 6. Advanced Usage

## Multiple Controllers

```java
controllers.add(new AnimationController<>(this, "movement", 0, this::movementPredicate));
controllers.add(new AnimationController<>(this, "attack", 0, this::attackPredicate));
```

## Triggered Animations

```java
controller.triggerableAnim("attack", ATTACK_ANIM);
controller.tryTriggerAnimation("attack");
```

## Server Sync

```java
level().broadcastEntityEvent(this, (byte) 1);
```

---

# 7. Bone Manipulation

```java
@Override
public void setCustomAnimations(MyEntity entity, long id, AnimationState<MyEntity> state) {
    var head = this.getAnimationProcessor().getBone("head");
    head.setRotX(state.getData(DataTickets.ENTITY_PITCH));
}
```

---

# 8. Other Use Cases

## Items
Implement:
GeoItem

## Block Entities
Implement:
GeoBlockEntity

## Armor
Use:
GeoArmorItem

---

# 9. Debugging

## Animation Not Playing
- Name mismatch
- Controller not registered
- Predicate not returning animation

## Invisible Model
- Wrong file path
- Invalid geo JSON
- Missing texture

## Animation Stuck
- Predicate always returns same state
- Controller cooldown too high

---

# 10. Best Practices

- Separate controllers (movement, combat)
- Use clear animation names: `idle`, `walk`, `attack`
- Keep bone hierarchy simple
- Test animations in Blockbench first
- Avoid large animation files

---

# 11. Q&A

## Q: Do I need vanilla models?
No.

## Q: Can I mix vanilla and GeckoLib?
Yes.

## Q: How to play animation once?
Use:
thenPlay()

## Q: How to detect movement?
state.isMoving()

## Q: Multiplayer desync?
You must sync from server.

## Q: Reuse animations?
Yes, across entities.

---

# 12. Learning Path

1. Idle + walk entity
2. Add attack animation
3. Triggered animations
4. Bone rotation
5. Multiplayer sync

---

# End
