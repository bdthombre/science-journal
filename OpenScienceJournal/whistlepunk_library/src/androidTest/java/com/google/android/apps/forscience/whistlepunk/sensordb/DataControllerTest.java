/*
 *  Copyright 2016 Google Inc. All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.google.android.apps.forscience.whistlepunk.sensordb;

import android.test.AndroidTestCase;

import com.google.android.apps.forscience.javalib.Consumer;
import com.google.android.apps.forscience.javalib.Success;
import com.google.android.apps.forscience.whistlepunk.Arbitrary;
import com.google.android.apps.forscience.whistlepunk.Clock;
import com.google.android.apps.forscience.whistlepunk.DataController;
import com.google.android.apps.forscience.whistlepunk.DataControllerImpl;
import com.google.android.apps.forscience.whistlepunk.ExplodingFactory;
import com.google.android.apps.forscience.whistlepunk.RecordingDataController;
import com.google.android.apps.forscience.whistlepunk.TestConsumers;
import com.google.android.apps.forscience.whistlepunk.data.GoosciSensorLayout;
import com.google.android.apps.forscience.whistlepunk.metadata.ApplicationLabel;
import com.google.android.apps.forscience.whistlepunk.metadata.BleSensorSpec;
import com.google.android.apps.forscience.whistlepunk.metadata.Experiment;
import com.google.android.apps.forscience.whistlepunk.metadata.ExperimentRun;
import com.google.android.apps.forscience.whistlepunk.metadata.ExternalSensorSpec;
import com.google.android.apps.forscience.whistlepunk.metadata.Project;
import com.google.android.apps.forscience.whistlepunk.metadata.RunStats;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class DataControllerTest extends AndroidTestCase {
    // TODO: once DataControllerImpl and RecordingDataControllerImpl are split, split tests, too.

    public void testAddScalarReading() {
        final InMemorySensorDatabase db = new InMemorySensorDatabase();
        RecordingDataController controller = db.makeSimpleRecordingController(
                new MemoryMetadataManager());
        controller.setDataErrorListenerForSensor("tag",
                new ExplodingFactory().makeListenerForOperation(""));

        controller.addScalarReading("tag", 0, 1234, 12.34);

        List<InMemorySensorDatabase.Reading> readings = db.getReadings(0);
        assertEquals(1, readings.size());
        InMemorySensorDatabase.Reading reading = readings.get(0);
        assertEquals("tag", reading.getDatabaseTag());
        assertEquals(1234, reading.getTimestampMillis());
        assertEquals(12.34, reading.getValue(), 0.001);
    }

    public void testStoreStats() {
        final InMemorySensorDatabase db = new InMemorySensorDatabase();

        final RunStats stats = new RunStats();

        final List<String> strings = Arbitrary.distinctStrings(3);
        final String key1 = strings.get(0);
        final String key2 = strings.get(1);
        final String key3 = strings.get(2);

        stats.putStat(key1, 1);
        stats.putStat(key2, 2);
        stats.putStat(key3, 3);

        MemoryMetadataManager mmm = new MemoryMetadataManager();
        db.makeSimpleRecordingController(mmm).setStats("startLabelId", "sensorId", stats,
                TestConsumers.<Success>expectingSuccess());

        final AtomicBoolean success = new AtomicBoolean(false);

        db.makeSimpleController(mmm).getStats("startLabelId", "sensorId",
                TestConsumers.expectingSuccess(new Consumer<RunStats>() {
                    @Override
                    public void take(RunStats runStats) {
                        assertEquals(Sets.newHashSet(key1, key2, key3), runStats.getKeys());

                        assertEquals(1.0, runStats.getStat(key1), 0.001);
                        assertEquals(2.0, runStats.getStat(key2), 0.001);
                        assertEquals(3.0, runStats.getStat(key3), 0.001);
                        success.set(true);
                    }
                }));

        assertTrue(success.get());
    }

    public void testStopRun() {
        InMemorySensorDatabase db = new InMemorySensorDatabase();
        MemoryMetadataManager manager = new MemoryMetadataManager();
        final DataController dc = db.makeSimpleController(manager);

        final StoringConsumer<Project> cProject = new StoringConsumer<>();
        dc.createProject(cProject);
        Project project = cProject.getValue();

        final StoringConsumer<Experiment> cExperiment = new StoringConsumer<>();
        dc.createExperiment(project, cExperiment);
        final Experiment experiment = cExperiment.getValue();

        final StoringConsumer<ApplicationLabel> cLabel = new StoringConsumer<>();
        dc.startRun(experiment, cLabel);
        final ApplicationLabel startLabel = cLabel.getValue();

        dc.stopRun(experiment, startLabel.getRunId(),
                new ArrayList<GoosciSensorLayout.SensorLayout>(),
                TestConsumers.<ApplicationLabel>expectingSuccess());

        final StoringConsumer<List<ExperimentRun>> cRuns = new StoringConsumer<>();
        dc.getExperimentRuns(experiment.getExperimentId(), false, cRuns);
        final List<ExperimentRun> runs = cRuns.getValue();

        assertEquals("Failed.  experiment id: " + experiment.getExperimentId(), 1, runs.size());
        final ExperimentRun experimentRun = runs.get(0);
        assertEquals(startLabel.getRunId(), experimentRun.getRunId());
    }

    public void testLayouts() {
        final DataController dc = new InMemorySensorDatabase().makeSimpleController(
                new MemoryMetadataManager());

        String experimentId = Arbitrary.string();
        List<GoosciSensorLayout.SensorLayout> layouts = new ArrayList<>();
        GoosciSensorLayout.SensorLayout layout = new GoosciSensorLayout.SensorLayout();
        layout.sensorId = Arbitrary.string();
        layouts.add(layout);
        dc.setSensorLayouts(experimentId, layouts, TestConsumers.<Success>expectingSuccess());

        StoringConsumer<List<GoosciSensorLayout.SensorLayout>> cLayouts = new StoringConsumer<>();
        dc.getSensorLayouts(experimentId, cLayouts);
        List<GoosciSensorLayout.SensorLayout> retrievedLayouts = cLayouts.getValue();

        assertEquals(1, retrievedLayouts.size());
        assertEquals(layout.sensorId, retrievedLayouts.get(0).sensorId);
    }

    public void testEnsureSensor() {
        BleSensorSpec spec = new BleSensorSpec("address", "name");
        final DataController dc = new InMemorySensorDatabase().makeSimpleController(
                new MemoryMetadataManager());
        StoringConsumer<String> cSensorId = new StoringConsumer<>();
        String expectedId = ExternalSensorSpec.getSensorId(spec, 0);

        dc.addOrGetExternalSensor(spec, cSensorId);
        assertEquals(expectedId, cSensorId.getValue());

        dc.addOrGetExternalSensor(spec, cSensorId);
        assertEquals(expectedId, cSensorId.getValue());

        StoringConsumer<ExternalSensorSpec> cSpec = new StoringConsumer<>();
        dc.getExternalSensorById(expectedId, cSpec);
        assertEquals(spec.toString(), cSpec.getValue().toString());
    }

    public void testAddDataError() {
        InMemorySensorDatabase failingDb = new InMemorySensorDatabase() {
            @Override
            public void addScalarReading(String databaseTag, int resolutionTier,
                    long timestampMillis, double value) {
                throw new RuntimeException("Could not add value " + value);
            }
        };
        RecordingDataController rdc = failingDb.makeSimpleRecordingController(
                new MemoryMetadataManager());
        StoringFailureListener listener = new StoringFailureListener();
        rdc.setDataErrorListenerForSensor("sensorId", listener);
        rdc.addScalarReading("sensorId", 0, 0, 3.0);

        // Different sensorId: won't be stored
        rdc.addScalarReading("notSensorId", 0, 0, 4.0);

        rdc.clearDataErrorListenerForSensor("sensorId");

        // Listener cleared: won't be stored
        rdc.addScalarReading("sensorId", 0, 0, 5.0);

        assertEquals("Could not add value 3.0", listener.exception.getMessage());
    }

    public void testSetStatsError() {
        MemoryMetadataManager failingMetadata = new MemoryMetadataManager() {
            @Override
            public void setStats(String startLabelId, String sensorId, RunStats stats) {
                throw new RuntimeException("Failed to store stats for " + sensorId);
            }
        };
        RecordingDataController rdc = new InMemorySensorDatabase().makeSimpleRecordingController(
                failingMetadata);
        StoringFailureListener listener = new StoringFailureListener();
        String sensorId = Arbitrary.string();

        rdc.setStats("runId", sensorId, new RunStats(),
                TestConsumers.<Success>expectingFailure(listener));

        assertEquals("Failed to store stats for " + sensorId, listener.exception.getMessage());
    }

    public void testReplaceChangesLayout() {
        final DataController dc = new InMemorySensorDatabase().makeSimpleController(
                new MemoryMetadataManager());
        GoosciSensorLayout.SensorLayout layout = new GoosciSensorLayout.SensorLayout();
        layout.sensorId = "oldSensorId";
        dc.addSensorToExperiment("experimentId", "oldSensorId",
                TestConsumers.<Success>expectingSuccess());
        dc.setSensorLayouts("experimentId", Lists.newArrayList(layout),
                TestConsumers.<Success>expectingSuccess());

        dc.replaceSensorInExperiment("experimentId", "oldSensorId", "newSensorId",
                TestConsumers.<Success>expectingSuccess());
        dc.getExternalSensorsByExperiment("experimentId", TestConsumers.expectingSuccess(
                new Consumer<Map<String, ExternalSensorSpec>>() {
                    @Override
                    public void take(Map<String, ExternalSensorSpec> map) {
                        assertEquals(Sets.newHashSet("newSensorId"), map.keySet());
                    }
                }));
        dc.getSensorLayouts("experimentId", TestConsumers.expectingSuccess(
                new Consumer<List<GoosciSensorLayout.SensorLayout>>() {
                    @Override
                    public void take(List<GoosciSensorLayout.SensorLayout> sensorLayouts) {
                        assertEquals(1, sensorLayouts.size());
                        assertEquals("newSensorId", sensorLayouts.get(0).sensorId);
                    }
                }));
    }

    public void testRemoveChangesLayout() {
        final DataController dc = new InMemorySensorDatabase().makeSimpleController(
                new MemoryMetadataManager());
        GoosciSensorLayout.SensorLayout layout = new GoosciSensorLayout.SensorLayout();
        layout.sensorId = "oldSensorId";
        dc.addSensorToExperiment("experimentId", "oldSensorId",
                TestConsumers.<Success>expectingSuccess());
        dc.setSensorLayouts("experimentId", Lists.newArrayList(layout),
                TestConsumers.<Success>expectingSuccess());
        dc.removeSensorFromExperiment("experimentId", "oldSensorId",
                TestConsumers.<Success>expectingSuccess());

        dc.getSensorLayouts("experimentId", TestConsumers.expectingSuccess(
                new Consumer<List<GoosciSensorLayout.SensorLayout>>() {
                    @Override
                    public void take(List<GoosciSensorLayout.SensorLayout> sensorLayouts) {
                        assertEquals(1, sensorLayouts.size());
                        assertEquals("", sensorLayouts.get(0).sensorId);
                    }
                }));
    }

    public void testGenerateLabelId() {
        IncrementableMonotonicClock clock = new IncrementableMonotonicClock();
        DataController dc = new DataControllerImpl(null, null, null, null, null,
                clock, null);
        clock.increment();

        String firstLabelId = dc.generateNewLabelId();
        String secondLabelId = dc.generateNewLabelId();
        String thirdLabelId = dc.generateNewLabelId();
        assertNotSame(firstLabelId, secondLabelId);
        assertNotSame(secondLabelId, thirdLabelId);

        clock.increment();
        String fourthLabelId = dc.generateNewLabelId();
        assertNotSame(thirdLabelId, fourthLabelId);
    }
}