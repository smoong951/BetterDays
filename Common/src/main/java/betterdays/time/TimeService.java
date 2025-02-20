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

package betterdays.time;

import java.util.Collection;

import betterdays.BetterDays;
import betterdays.registry.TimeEffectsRegistry;
import betterdays.registry.RegistryObject;
import betterdays.config.ConfigHandler;
import betterdays.platform.Services;
import betterdays.time.effects.TimeEffect;
import betterdays.utils.MathUtils;
import betterdays.wrappers.ServerLevelWrapper;
import betterdays.wrappers.TimePacketWrapper;

/**
 * Handles the Better Days time and sleep functionality for a level.
 */
public class TimeService {

    /** Time of day when the sun rises above the horizon. */
    public static final Time DAY_START = new Time(ConfigHandler.Common.dayStart());

    /** Time of day when the sun sets below the horizon. */
    public static final Time NIGHT_START = new Time(ConfigHandler.Common.nightStart());

    // The largest number of lunar cycles that can be stored in an int
    private static final int OVERFLOW_THRESHOLD = 11184 * Time.LUNAR_CYCLE_TICKS;

    /** The level managed by this {@code TimeService}. */
    public final ServerLevelWrapper level;
    /** The {@code SleepStatus} object for this level. */
    public final SleepStatus sleepStatus;

    private double timeDecimalAccumulator = 0;

    /**
     * Creates a new instance.
     *
     * @param level  the wrapped level whose time this object should manage
     */
    public TimeService(ServerLevelWrapper level) {
        this.level = level;
        this.sleepStatus = new SleepStatus(ConfigHandler.Common::enableSleepFeature);

        this.level.setSleepStatus(this.sleepStatus);
    }

    /**
     * Performs all time, sleep, and weather calculations. Should run once per tick.
     */
    public void tick() {
        if (!level.daylightRuleEnabled()) {
            return;
        }

        Time oldTime = getDayTime();
        Time deltaTime = tickTime();
        Time time = getDayTime();

        TimeContext context = new TimeContext(this, time, deltaTime);
        getActiveTimeEffects().forEach(effect -> effect.get().onTimeTick(context));

        if (ConfigHandler.Common.enableSleepFeature() && !sleepStatus.allAwake() && Time.crossedMorning(oldTime, time)) {
            handleMorning();
        }

        preventTimeOverflow();
        broadcastTime();
        vanillaTimeCompensation();
    }

    private void handleMorning() {
        long time = level.get().getDayTime();

        Services.PLATFORM.onSleepFinished(level, time);
        sleepStatus.removeAllSleepers();
        level.wakeUpAllPlayers();

        if (level.weatherRuleEnabled() && ConfigHandler.Common.clearWeatherOnWake()) {
            level.stopWeather();
        }

        BetterDays.LOGGER.debug("Sleep cycle complete on dimension: {}.",
                level.get().dimension().location());
    }

    /**
     * This method compensates for time changes made by the vanilla server every tick.
     *
     * The vanilla server increments time at a rate of 1 every tick. Since this functionality
     * conflicts with this mod's time changes, and this functionality cannot be prevented, this
     * method should be called at the end of the {@code START} phase of every world tick to undo
     * this vanilla progression.
     */
    private void vanillaTimeCompensation() {
        level.get().setDayTime(level.get().getDayTime() - 1);
    }

    /**
     * Prevents time value from getting too large by essentially keeping it modulo a multiple of the
     * lunar cycle.
     */
    private void preventTimeOverflow() {
        long time = level.get().getDayTime();
        if (time > OVERFLOW_THRESHOLD) {
            level.get().setDayTime(time - OVERFLOW_THRESHOLD);
        }
    }

    /**
     * Progresses time in this {@link #level} based on the current time-speed.
     * This method should be called every tick.
     *
     * @return the amount of time that elapsed
     */
    private Time tickTime() {
        Time time = getDayTime();

        Time timeDelta = new Time(getTimeSpeed(time));
        timeDelta = correctForOvershoot(time, timeDelta);

        setDayTime(time.add(timeDelta));
        return timeDelta;
    }

    /**
     * Checks to see if the time-speed will change after elapsing time by {@code timeDelta}, and
     * correct for any overshooting (or undershooting) based on the new speed.
     *
     * @param time  the current time
     * @param timeDelta  the proposed amount of time to elapse
     * @return the adjusted amount of time to elapse
     */
    private Time correctForOvershoot(Time time, Time timeDelta) {
        Time nextTime = time.add(timeDelta);
        Time timeOfDay = time.timeOfDay();
        Time nextTimeOfDay = nextTime.timeOfDay();

        if (sleepStatus.allAwake()) {
            // day to night transition
            if (NIGHT_START.betweenMod(timeOfDay, nextTimeOfDay)) {
                double nextTimeSpeed = getTimeSpeed(nextTime);
                Time timeUntilBreakpoint = NIGHT_START.subtract(timeOfDay);
                double breakpointRatio = 1 - timeUntilBreakpoint.divide(timeDelta);

                return timeUntilBreakpoint.add(nextTimeSpeed * breakpointRatio);
            }

            // day to night transition
            if (DAY_START.betweenMod(timeOfDay, nextTimeOfDay)) {
                double nextTimeSpeed = getTimeSpeed(nextTime);
                Time timeUntilBreakpoint = DAY_START.subtract(timeOfDay);
                double breakpointRatio = 1 - timeUntilBreakpoint.divide(timeDelta);

                return timeUntilBreakpoint.add(nextTimeSpeed * breakpointRatio);
            }
        } else {
            // morning transition
            Time timeUntilMorning = Time.DAY_LENGTH.subtract(timeOfDay);
            if (timeUntilMorning.compareTo(timeDelta) < 0) {
                double nextTimeSpeed = ConfigHandler.Common.daySpeed();
                double breakpointRatio = 1 - timeUntilMorning.divide(timeDelta);

                return timeUntilMorning.add(nextTimeSpeed * breakpointRatio);
            }
        }

        return timeDelta;
    }

    /**
     * Calculates the current time-speed multiplier based on the time-of-day and number of sleeping
     * players.
     *
     * Accepts time as a parameter to allow for prediction of other times. Prediction of times other
     * than the current time may not be accurate due to sleeping player changes.
     *
     * A return value of 1 is equivalent to vanilla time speed.
     *
     * @param time  the time at which to calculate the time-speed
     * @return the time-speed
     */
    public double getTimeSpeed(Time time) {
        if (!ConfigHandler.Common.enableSleepFeature() || sleepStatus.allAwake()) {
            if (time.equals(DAY_START) || time.timeOfDay().betweenMod(DAY_START, NIGHT_START)) {
                return ConfigHandler.Common.daySpeed();
            } else {
                return ConfigHandler.Common.nightSpeed();
            }
        }

        if (sleepStatus.allAsleep() && ConfigHandler.Common.sleepSpeedAll() >= 0) {
            return ConfigHandler.Common.sleepSpeedAll();
        }

        double sleepRatio = sleepStatus.ratio();
        double curve = ConfigHandler.Common.sleepSpeedCurve();
        double speedRatio = MathUtils.normalizedTunableSigmoid(sleepRatio, curve);

        double sleepSpeedMin = ConfigHandler.Common.sleepSpeedMin();
        double sleepSpeedMax = ConfigHandler.Common.sleepSpeedMax();
        double multiplier = MathUtils.lerp(speedRatio, sleepSpeedMin, sleepSpeedMax);

        return multiplier;
    }

    /**
     * {@return this level's time as an instance of {@link Time}}
     */
    public Time getDayTime() {
        return new Time(level.get().getDayTime(), timeDecimalAccumulator);
    }

    /**
     * Sets this level's 'daytime' to the integer component of {@code time}.
     * @param time  the time to set
     * @return the new time
     */
    public Time setDayTime(Time time) {
        timeDecimalAccumulator = time.fractionalValue();
        level.get().setDayTime(time.longValue());
        return time;
    }

    /**
     * Broadcasts the current time to all players who observe it.
     */
    public void broadcastTime() {
        TimePacketWrapper timePacket = TimePacketWrapper.create(level);
        level.get().getServer().getPlayerList().getPlayers().stream()
                .filter(player -> managesLevel(new ServerLevelWrapper(player.level())))
                .forEach(player -> player.connection.send(timePacket.get()));
    }

    /**
     * Returns true if {@code levelToCheck} has its time managed by this object, or false otherwise.
     * If this object is managing the overworld, this method will return true for all derived
     * levels.
     *
     * @param levelToCheck  the level to check
     * @return true if {@code levelToCheck} has its time managed by this object, or false otherwise.
     */
    public boolean managesLevel(ServerLevelWrapper levelToCheck) {
        if (level.get().equals(levelToCheck.get())) {
            return true;
        } else if (level.get().equals(level.get().getServer().overworld())
                && ServerLevelWrapper.isDerived(levelToCheck.get())) {
            return true;
        } else {
            return false;
        }
    }

    private Collection<RegistryObject<TimeEffect>> getActiveTimeEffects() {
        return TimeEffectsRegistry.TIME_EFFECT_REGISTRY.getEntries();
    }

}
