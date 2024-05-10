package com.github.exopandora.shouldersurfing.client;

import com.github.exopandora.shouldersurfing.config.Config;
import com.github.exopandora.shouldersurfing.config.Perspective;
import com.github.exopandora.shouldersurfing.math.Vec2f;
import com.github.exopandora.shouldersurfing.api.impl.ShoulderSurfingRegistrar;
import com.github.exopandora.shouldersurfing.mixinducks.OptionsDuck;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class ShoulderInstance
{
	private static final ShoulderInstance INSTANCE = new ShoulderInstance();
	private boolean doShoulderSurfing;
	private boolean isTemporaryFirstPerson;
	private boolean isAiming;
	private double offsetX = Config.CLIENT.getOffsetX();
	private double offsetY = Config.CLIENT.getOffsetY();
	private double offsetZ = Config.CLIENT.getOffsetZ();
	private double lastOffsetX = Config.CLIENT.getOffsetX();
	private double lastOffsetY = Config.CLIENT.getOffsetY();
	private double lastOffsetZ = Config.CLIENT.getOffsetZ();
	private double targetOffsetX = Config.CLIENT.getOffsetX();
	private double targetOffsetY = Config.CLIENT.getOffsetY();
	private double targetOffsetZ = Config.CLIENT.getOffsetZ();
	private boolean isFreeLooking = false;
	private float freeLookYRot = 0.0F;
	
	private ShoulderInstance()
	{
		super();
	}
	
	public void init()
	{
		if(Config.CLIENT.doRememberLastPerspective())
		{
			this.changePerspective(Config.CLIENT.getDefaultPerspective());
		}
	}
	
	public void tick()
	{
		if(!Perspective.FIRST_PERSON.equals(Perspective.current()))
		{
			this.isTemporaryFirstPerson = false;
		}
		
		if(Config.CLIENT.getCrosshairType().doSwitchPerspective(this.isAiming) && this.doShoulderSurfing)
		{
			this.changePerspective(Perspective.FIRST_PERSON);
			this.isTemporaryFirstPerson = true;
		}
		else if(!Config.CLIENT.getCrosshairType().doSwitchPerspective(this.isAiming) && Perspective.FIRST_PERSON.equals(Perspective.current()) && this.isTemporaryFirstPerson)
		{
			this.changePerspective(Perspective.SHOULDER_SURFING);
		}
		
		this.lastOffsetX = this.offsetX;
		this.lastOffsetY = this.offsetY;
		this.lastOffsetZ = this.offsetZ;
		
		this.offsetX = this.lastOffsetX + (this.targetOffsetX - this.lastOffsetX) * Config.CLIENT.getCameraTransitionSpeedMultiplier();		
		this.offsetY = this.lastOffsetY + (this.targetOffsetY - this.lastOffsetY) * Config.CLIENT.getCameraTransitionSpeedMultiplier();
		this.offsetZ = this.lastOffsetZ + (this.targetOffsetZ - this.lastOffsetZ) * Config.CLIENT.getCameraTransitionSpeedMultiplier();
		
		this.isFreeLooking = KeyHandler.FREE_LOOK.isDown() && !this.isAiming;
		
		if(!this.isFreeLooking)
		{
			this.freeLookYRot = ShoulderRenderer.getInstance().getCameraYRot();
		}
	}
	
	private boolean shouldEntityAimAtTarget(LivingEntity cameraEntity, Minecraft minecraft)
	{
		return this.isAiming && Config.CLIENT.getCrosshairType().isAimingDecoupled() || !this.isAiming && Config.CLIENT.isCameraDecoupled() &&
			(isUsingItem(cameraEntity) || !cameraEntity.isFallFlying() && (isInteracting(cameraEntity, minecraft) || isAttacking(minecraft) || isPicking(minecraft)));
	}
	
	private static boolean isUsingItem(LivingEntity cameraEntity)
	{
		return Config.CLIENT.doTurnPlayerWhenUsingItem() && cameraEntity.isUsingItem() && !cameraEntity.getUseItem().has(DataComponents.FOOD);
	}
	
	private static boolean isInteracting(LivingEntity cameraEntity, Minecraft minecraft)
	{
		return Config.CLIENT.doTurnPlayerWhenInteracting() && minecraft.options.keyUse.isDown() && !cameraEntity.isUsingItem() &&
			(!Config.CLIENT.doRequireTargetTurningPlayerWhenInteracting() || hasTarget(minecraft));
	}
	
	private static boolean isAttacking(Minecraft minecraft)
	{
		return Config.CLIENT.doTurnPlayerWhenAttacking() && minecraft.options.keyAttack.isDown() &&
			(!Config.CLIENT.doRequireTargetTurningPlayerWhenAttacking() || hasTarget(minecraft));
	}
	
	private static boolean isPicking(Minecraft minecraft)
	{
		return Config.CLIENT.doTurnPlayerWhenPicking() && minecraft.options.keyPickItem.isDown() &&
			(!Config.CLIENT.doRequireTargetTurningPlayerWhenPicking() || hasTarget(minecraft));
	}
	
	private static boolean hasTarget(Minecraft minecraft)
	{
		return minecraft.hitResult != null && minecraft.hitResult.getType() != HitResult.Type.MISS;
	}
	
	public void onMovementInputUpdate(Input input)
	{
		Minecraft minecraft = Minecraft.getInstance();
		Entity cameraEntity = minecraft.getCameraEntity();
		Vec2f moveVector = new Vec2f(input.leftImpulse, input.forwardImpulse);
		this.isAiming = isHoldingAdaptiveItem(minecraft, cameraEntity);
		
		if(this.doShoulderSurfing && this.isFreeLooking)
		{
			moveVector.rotateDegrees(Mth.degreesDifference(cameraEntity.getYRot(), this.freeLookYRot));
			input.leftImpulse = moveVector.x();
			input.forwardImpulse = moveVector.y();
		}
		else if(this.doShoulderSurfing && minecraft.player != null && cameraEntity == minecraft.player)
		{
			Player player = minecraft.player;
			ShoulderRenderer renderer = ShoulderRenderer.getInstance();
			boolean shouldAimAtTarget = this.shouldEntityAimAtTarget(player, minecraft);
			boolean hasImpulse = moveVector.lengthSquared() > 0;
			float xRot = player.getXRot();
			float yRot = player.getYRot();
			float yRotO = yRot;
			
			if(shouldAimAtTarget)
			{
				Camera camera = minecraft.gameRenderer.getMainCamera();
				double rayTraceDistance = Config.CLIENT.getCrosshairType().isAimingDecoupled() ? 400 : Config.CLIENT.getCustomRaytraceDistance();
				boolean isCrosshairDynamic = ShoulderInstance.getInstance().isCrosshairDynamic(player);
				HitResult hitResult = ShoulderRayTracer.traceBlocksAndEntities(camera, player, rayTraceDistance, ClipContext.Fluid.NONE, 1.0F, true, !isCrosshairDynamic);
				Vec3 eyePosition = player.getEyePosition();
				double dx = hitResult.getLocation().x - eyePosition.x;
				double dy = hitResult.getLocation().y - eyePosition.y;
				double dz = hitResult.getLocation().z - eyePosition.z;
				double xz = Math.sqrt(dx * dx + dz * dz);
				xRot = (float) Mth.wrapDegrees(-Mth.atan2(dy, xz) * Mth.RAD_TO_DEG);
				yRot = (float) Mth.wrapDegrees(Mth.atan2(dz, dx) * Mth.RAD_TO_DEG - 90.0F);
			}
			else if(Config.CLIENT.isCameraDecoupled() && (this.isAiming && !Config.CLIENT.getCrosshairType().isAimingDecoupled() || player.isFallFlying()) || !Config.CLIENT.isCameraDecoupled())
			{
				xRot = renderer.getCameraXRot();
				yRot = renderer.getCameraYRot();
			}
			else if(hasImpulse)
			{
				float cameraXRot = renderer.getCameraXRot();
				float cameraYRot = renderer.getCameraYRot();
				Vec2f rotated = moveVector.rotateDegrees(cameraYRot);
				xRot = cameraXRot * 0.5F;
				yRot = (float) Mth.wrapDegrees(Math.atan2(-rotated.x(), rotated.y()) * Mth.RAD_TO_DEG);
				yRot = yRotO + Mth.degreesDifference(yRotO, yRot) * 0.25F;
			}
			
			if(hasImpulse)
			{
				moveVector = moveVector.rotateDegrees(Mth.degreesDifference(yRot, renderer.getCameraYRot()));
			}
			
			player.setXRot(xRot);
			player.setYRot(yRot);
			
			input.leftImpulse = moveVector.x();
			input.forwardImpulse = moveVector.y();
		}
	}
	
	private static boolean isHoldingAdaptiveItem(Minecraft minecraft, Entity entity)
	{
		if(entity instanceof LivingEntity living)
		{
			return ShoulderSurfingRegistrar.getInstance().getAdaptiveItemCallbacks().stream().anyMatch(callback -> callback.isHoldingAdaptiveItem(minecraft, living));
		}
		
		return false;
	}
	
	public void changePerspective(Perspective perspective)
	{
		((OptionsDuck) Minecraft.getInstance().options).shouldersurfing$setCameraTypeDirect(perspective.getCameraType());
		this.setShoulderSurfing(Perspective.SHOULDER_SURFING.equals(perspective));
	}
	
	private void onShoulderSurfingActivated()
	{
		Entity cameraEntity = Minecraft.getInstance().getCameraEntity();
		
		if(cameraEntity != null)
		{
			ShoulderRenderer.getInstance().resetState(cameraEntity);
		}
	}
	
	public boolean isCrosshairDynamic(Entity entity)
	{
		return this.doShoulderSurfing && Config.CLIENT.getCrosshairType().isDynamic(entity, this.isAiming);
	}
	
	public boolean doShoulderSurfing()
	{
		return this.doShoulderSurfing;
	}
	
	public void setShoulderSurfing(boolean doShoulderSurfing)
	{
		if(!this.doShoulderSurfing && doShoulderSurfing)
		{
			this.onShoulderSurfingActivated();
		}
		
		this.doShoulderSurfing = doShoulderSurfing;
	}
	
	public boolean isAiming()
	{
		return this.isAiming;
	}
	
	public Vec3 getOffset()
	{
		return new Vec3(this.getOffsetX(), this.getOffsetZ(), this.getOffsetY());
	}
	
	public double getOffsetX()
	{
		return this.offsetX;
	}
	
	public double getOffsetXOld()
	{
		return this.lastOffsetX;
	}
	
	public double getOffsetY()
	{
		return this.offsetY;
	}
	
	public double getOffsetYOld()
	{
		return this.lastOffsetY;
	}
	
	public double getOffsetZ()
	{
		return this.offsetZ;
	}
	
	public double getOffsetZOld()
	{
		return this.lastOffsetZ;
	}
	
	public void setTargetOffsetX(double targetOffsetX)
	{
		this.targetOffsetX = targetOffsetX;
	}
	
	public void setTargetOffsetY(double targetOffsetY)
	{
		this.targetOffsetY = targetOffsetY;
	}
	
	public void setTargetOffsetZ(double targetOffsetZ)
	{
		this.targetOffsetZ = targetOffsetZ;
	}
	
	public boolean isFreeLooking()
	{
		return this.isFreeLooking;
	}
	
	public static ShoulderInstance getInstance()
	{
		return INSTANCE;
	}
}
