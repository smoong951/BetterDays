/*
 * Derived from Hourglass
 * https://github.com/DuckyCrayfish/hourglass
 * Copyright (C) 2021 Nick Iacullo
 *
 * This file is part of Better Days.
 *
 * Better Days is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Better Days is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Better Days.  If not, see <https://www.gnu.org/licenses/>.
 */

package betterdays.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.LevelAccessor;

import betterdays.config.ConfigHandler;
import betterdays.time.Time;
import betterdays.wrappers.ClientLevelWrapper;

/**
 * This class detects time updates from the server and interpolates the changes over time to smooth
 * out any time jumps.
 */
public class TimeInterpolator {

    /** The current {@code TimeInterpolator} instance running. */
    public static TimeInterpolator instance;

    /** The level whose time this object interpolates. */
    public final ClientLevelWrapper level;
    private boolean initialized;
    private long targetTime;
    private float timeVelocity;
    private long lastTime;
    private float lastPartialTickTime;

    /**
     * Event listener that is called when a new level is loaded.
     */
    public static void onWorldLoad(LevelAccessor level) {
        if (ClientLevelWrapper.isClientLevel(level)) {
            ClientLevelWrapper levelWrapper = new ClientLevelWrapper(level);

            instance = new TimeInterpolator(levelWrapper);
        }
    }

    /**
     * Event listener that is called when a level is unloaded.
     */
    public static void onWorldUnload(LevelAccessor level) {
        if (instance != null && instance.level.get().equals(level)) {
            instance = null;
        }
    }

    /**
     * Event listener that is called on every render tick
     */
    public static void onRenderTickEvent(float renderTickTime) {
        Minecraft minecraft = Minecraft.getInstance();

        if (!minecraft.isPaused()
                && instance != null
                && instance.level.get().equals(minecraft.level)
                && !ConfigHandler.Client.getBlacklistDimensions().contains(instance.level.get().dimension().location())) {
            instance.partialTick(renderTickTime);
        }
    }

    /**
     * Event listener that is called on every tick. This event continues to
     * be dispatched while a player is in the main or pause menu.
     */
    public static void onClientTickEvent(Minecraft minecraft) {
        if (!minecraft.isPaused()
                && instance != null
                && instance.level.get().equals(minecraft.level)) {

            instance.undoVanillaTimeTicks();
        }
    }

    /**
     * Creates a new instance.
     *
     * @param level  the wrapped level whose time this object will manage
     */
    public TimeInterpolator(ClientLevelWrapper level) {
        this.level = level;
        this.initialized = false;
    }

    /**
     * Initializes variables that need to be set after ticks have started processing.
     */
    private void init() {
        long time = level.get().getDayTime();
        this.targetTime = time;
        this.lastTime = time;
        this.initialized = true;
    }

    /**
     * This method is used to smooth out updates to the time of day on a frame-by-frame basis so
     * that the time of day does not appear to stutter when the time speed is fast.
     *
     * @param partialTickTime  fractional percentage of progress from last tick to the next one
     */
    public void partialTick(float partialTickTime) {
        if (!initialized) {
            init();
        }

        float tickTimeDelta = getPartialTimeDelta(partialTickTime);
        updateTargetTime();
        interpolateTime(tickTimeDelta);
    }

    /**
     * Calculates the amount of time that has passed since this method was last run. Measured in
     * fractions of ticks. This method assumes it is being ran at least once per tick.
     *
     * @param partialTickTime  the current partial tick time
     * @return the time that has passed since last run.
     */
    private float getPartialTimeDelta(float partialTickTime) {
        float partialTimeDelta = partialTickTime - lastPartialTickTime;
        if (partialTimeDelta < 0) partialTimeDelta += 1;
        lastPartialTickTime = partialTickTime;
        return partialTimeDelta;
    }

    /**
     * Interpolate time changes changes on a frame-by-frame basis to smooth out time updates from
     * the server.
     *
     * @param tickTimeDelta  the amount of time that has passed since this method was last run.
     *                       Measured in fractions of ticks.
     */
    private void interpolateTime(final float tickTimeDelta) {
        long time = level.get().getDayTime();

        final float duration = 1f; // Interpolate over 1 tick.
        final float omega = 2F / duration;
        final float x = omega * tickTimeDelta;
        final float exp = 1F / (1F + x + 0.48F * x * x + 0.235F * x * x * x);
        final float change = time - targetTime;

        float temp = (timeVelocity + omega * change) * tickTimeDelta;
        time = targetTime + (long) ((change + temp) * exp);
        timeVelocity = (timeVelocity - omega * temp) * exp;

        // Correct for overshoot.
        if (change < 0.0F == time > targetTime) {
            time = targetTime;
            timeVelocity = 0.0F;
        }

        setDayTime(time);
    }

    /**
     * When the server updates the client time, it does so by directly changing the current day
     * time. To interpolate changes received from the server over multiple frames (instead of
     * accepting the jump in time), this method detects time updates from the server, resets time
     * to where it was originally, and then updates the interpolation target time instead.
     *
     * To prevent interpolation distances larger than a single day (which could be jarring) this
     * method jumps to same day as the interpolation target and interpolates from there.
     */
    private void updateTargetTime() {
        long time = level.get().getDayTime();

        // Packet received, update interpolation target and reset current time.
        if (time != lastTime) {
            targetTime = time;

            // Prevent large interpolation distances
            long discrepancy = lastTime - time;
            if (Math.abs(discrepancy) > Time.DAY_TICKS) {
                long newTimeOfDay = Time.timeOfDay(time);
                long oldTimeOfDay = Time.timeOfDay(lastTime);
                lastTime = time - newTimeOfDay + oldTimeOfDay;
            }

            level.get().getLevelData().setDayTime(lastTime);
        }
    }

    /**
     * Updates the time of day in {@link #level}, while keeping track of the last time set using
     * this method.
     *
     * @param time  the time of day to set
     */
    private void setDayTime(long time) {
        level.get().getLevelData().setDayTime(time);
        lastTime = time;
    }

    /**
     * The vanilla client increments time every tick, which messes with our time interpolation. Call
     * this method at the end of every tick to undo this.
     */
    private void undoVanillaTimeTicks() {
        if (level.daylightRuleEnabled()) {
            level.get().getLevelData().setDayTime(level.get().getDayTime() - 1);
        }
    }

}
