package com.orientechnologies.orient.core.storage.impl.local.paginated;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.orientechnologies.common.directmemory.ODirectMemory;
import com.orientechnologies.common.directmemory.ODirectMemoryFactory;
import com.orientechnologies.common.util.MersenneTwisterFast;
import com.orientechnologies.orient.core.storage.impl.local.paginated.wal.updatePageRecord.OFullPageDiff;
import com.orientechnologies.orient.core.storage.impl.local.paginated.wal.updatePageRecord.OPageDiff;
import com.orientechnologies.orient.core.version.ORecordVersion;
import com.orientechnologies.orient.core.version.OVersionFactory;

/**
 * @author Andrey Lomakin
 * @since 20.03.13
 */
@Test
public class LocalPageTest {
  private static final int SYSTEM_OFFSET = 24;
  private ODirectMemory    directMemory  = ODirectMemoryFactory.INSTANCE.directMemory();

  public void testAddOneRecord() throws Exception {
    long pagePointer = directMemory.allocate(new byte[OLocalPage.PAGE_SIZE]);
    try {
      OLocalPage localPage = new OLocalPage(pagePointer, true, OLocalPage.TrackMode.BOTH);
      int freeSpace = localPage.getFreeSpace();
      Assert.assertEquals(localPage.getRecordsCount(), 0);

      ORecordVersion recordVersion = OVersionFactory.instance().createVersion();
      recordVersion.increment();

      int position = localPage.appendRecord(recordVersion, new byte[] { 1, 2, 3, 4, 5, 6, 5, 4, 3, 2, 1 }, false);
      Assert.assertEquals(localPage.getRecordsCount(), 1);
      Assert.assertEquals(localPage.getRecordSize(0), 11);
      Assert.assertEquals(position, 0);
      Assert.assertEquals(localPage.getFreeSpace(), freeSpace - (27 + OVersionFactory.instance().getVersionSize()));
      Assert.assertFalse(localPage.isDeleted(0));
      Assert.assertEquals(localPage.getRecordVersion(0), recordVersion);

      int pageOffset = localPage.getRecordPageOffset(0);
      Assert.assertEquals(localPage.getBinaryValue(pageOffset, 11), new byte[] { 1, 2, 3, 4, 5, 6, 5, 4, 3, 2, 1 });

      assertChangesTracking(localPage, pagePointer);
    } finally {
      directMemory.free(pagePointer);
    }
  }

  public void testAddTreeRecords() throws Exception {
    long pagePointer = directMemory.allocate(new byte[OLocalPage.PAGE_SIZE]);
    try {
      OLocalPage localPage = new OLocalPage(pagePointer, true, OLocalPage.TrackMode.BOTH);
      int freeSpace = localPage.getFreeSpace();

      Assert.assertEquals(localPage.getRecordsCount(), 0);

      ORecordVersion recordVersion = OVersionFactory.instance().createVersion();
      recordVersion.increment();

      int positionOne = localPage.appendRecord(recordVersion, new byte[] { 1, 2, 3, 4, 5, 6, 5, 4, 3, 2, 1 }, false);
      int positionTwo = localPage.appendRecord(recordVersion, new byte[] { 2, 2, 3, 4, 5, 6, 5, 4, 3, 2, 2 }, false);
      int positionThree = localPage.appendRecord(recordVersion, new byte[] { 3, 2, 3, 4, 5, 6, 5, 4, 3, 2, 3 }, false);

      Assert.assertEquals(localPage.getRecordsCount(), 3);
      Assert.assertEquals(positionOne, 0);
      Assert.assertEquals(positionTwo, 1);
      Assert.assertEquals(positionThree, 2);

      Assert.assertEquals(localPage.getFreeSpace(), freeSpace - (3 * (27 + OVersionFactory.instance().getVersionSize())));
      Assert.assertFalse(localPage.isDeleted(0));
      Assert.assertFalse(localPage.isDeleted(1));
      Assert.assertFalse(localPage.isDeleted(2));

      int pageOffset = localPage.getRecordPageOffset(0);
      Assert.assertEquals(localPage.getBinaryValue(pageOffset, 11), new byte[] { 1, 2, 3, 4, 5, 6, 5, 4, 3, 2, 1 });
      Assert.assertEquals(localPage.getRecordSize(0), 11);
      Assert.assertEquals(localPage.getRecordVersion(0), recordVersion);

      pageOffset = localPage.getRecordPageOffset(1);
      Assert.assertEquals(localPage.getBinaryValue(pageOffset, 11), new byte[] { 2, 2, 3, 4, 5, 6, 5, 4, 3, 2, 2 });
      Assert.assertEquals(localPage.getRecordSize(0), 11);
      Assert.assertEquals(localPage.getRecordVersion(1), recordVersion);

      pageOffset = localPage.getRecordPageOffset(2);
      Assert.assertEquals(localPage.getBinaryValue(pageOffset, 11), new byte[] { 3, 2, 3, 4, 5, 6, 5, 4, 3, 2, 3 });
      Assert.assertEquals(localPage.getRecordSize(0), 11);
      Assert.assertEquals(localPage.getRecordVersion(2), recordVersion);

      assertChangesTracking(localPage, pagePointer);
    } finally {
      directMemory.free(pagePointer);
    }
  }

  public void testAddFullPage() throws Exception {
    long pagePointer = directMemory.allocate(new byte[OLocalPage.PAGE_SIZE]);
    try {
      OLocalPage localPage = new OLocalPage(pagePointer, true, OLocalPage.TrackMode.BOTH);

      ORecordVersion recordVersion = OVersionFactory.instance().createVersion();
      recordVersion.increment();

      List<Integer> positions = new ArrayList<Integer>();
      int lastPosition;
      byte counter = 0;
      int freeSpace = localPage.getFreeSpace();
      do {
        lastPosition = localPage.appendRecord(recordVersion, new byte[] { counter, counter, counter }, false);
        if (lastPosition >= 0) {
          Assert.assertEquals(lastPosition, positions.size());
          positions.add(lastPosition);
          counter++;

          Assert.assertEquals(localPage.getFreeSpace(), freeSpace - (19 + OVersionFactory.instance().getVersionSize()));
          freeSpace = localPage.getFreeSpace();
        }
      } while (lastPosition >= 0);

      Assert.assertEquals(localPage.getRecordsCount(), positions.size());

      counter = 0;
      for (int position : positions) {
        int pageOffset = localPage.getRecordPageOffset(position);

        Assert.assertEquals(localPage.getBinaryValue(pageOffset, 3), new byte[] { counter, counter, counter });
        Assert.assertEquals(localPage.getRecordSize(position), 3);
        Assert.assertEquals(localPage.getRecordVersion(position), recordVersion);
        counter++;
      }

      assertChangesTracking(localPage, pagePointer);
    } finally {
      directMemory.free(pagePointer);
    }
  }

  public void testDeleteAddLowerVersion() throws Exception {
    long pagePointer = directMemory.allocate(new byte[OLocalPage.PAGE_SIZE]);
    try {
      OLocalPage localPage = new OLocalPage(pagePointer, true, OLocalPage.TrackMode.BOTH);

      ORecordVersion recordVersion = OVersionFactory.instance().createVersion();
      recordVersion.increment();
      recordVersion.increment();

      int position = localPage.appendRecord(recordVersion, new byte[] { 1, 2, 3, 4, 5, 6, 5, 4, 3, 2, 1 }, false);

      Assert.assertTrue(localPage.deleteRecord(position));

      ORecordVersion newRecordVersion = OVersionFactory.instance().createVersion();

      Assert
          .assertEquals(localPage.appendRecord(newRecordVersion, new byte[] { 2, 2, 2, 4, 5, 6, 5, 4, 2, 2, 2 }, false), position);

      int recordPageOffset = localPage.getRecordPageOffset(position);
      int recordSize = localPage.getRecordSize(position);
      Assert.assertEquals(recordSize, 11);

      recordVersion.increment();
      Assert.assertEquals(localPage.getRecordVersion(position), recordVersion);
      Assert.assertEquals(localPage.getBinaryValue(recordPageOffset, recordSize), new byte[] { 2, 2, 2, 4, 5, 6, 5, 4, 2, 2, 2 });

      assertChangesTracking(localPage, pagePointer);
    } finally {
      directMemory.free(pagePointer);
    }
  }

  public void testDeleteAddLowerVersionKeepTombstoneVersion() throws Exception {
    long pagePointer = directMemory.allocate(new byte[OLocalPage.PAGE_SIZE]);
    try {
      OLocalPage localPage = new OLocalPage(pagePointer, true, OLocalPage.TrackMode.BOTH);

      ORecordVersion recordVersion = OVersionFactory.instance().createVersion();
      recordVersion.increment();
      recordVersion.increment();

      int position = localPage.appendRecord(recordVersion, new byte[] { 1, 2, 3, 4, 5, 6, 5, 4, 3, 2, 1 }, false);

      Assert.assertTrue(localPage.deleteRecord(position));

      ORecordVersion newRecordVersion = OVersionFactory.instance().createVersion();

      Assert.assertEquals(localPage.appendRecord(newRecordVersion, new byte[] { 2, 2, 2, 4, 5, 6, 5, 4, 2, 2, 2 }, true), position);

      int recordPageOffset = localPage.getRecordPageOffset(position);
      int recordSize = localPage.getRecordSize(position);
      Assert.assertEquals(recordSize, 11);

      Assert.assertEquals(localPage.getRecordVersion(position), recordVersion);
      Assert.assertEquals(localPage.getBinaryValue(recordPageOffset, recordSize), new byte[] { 2, 2, 2, 4, 5, 6, 5, 4, 2, 2, 2 });

      assertChangesTracking(localPage, pagePointer);
    } finally {
      directMemory.free(pagePointer);
    }
  }

  public void testDeleteAddBiggerVersion() throws Exception {
    long pagePointer = directMemory.allocate(new byte[OLocalPage.PAGE_SIZE]);
    try {
      OLocalPage localPage = new OLocalPage(pagePointer, true, OLocalPage.TrackMode.BOTH);

      ORecordVersion recordVersion = OVersionFactory.instance().createVersion();
      recordVersion.increment();
      recordVersion.increment();

      int position = localPage.appendRecord(recordVersion, new byte[] { 1, 2, 3, 4, 5, 6, 5, 4, 3, 2, 1 }, false);

      Assert.assertTrue(localPage.deleteRecord(position));

      ORecordVersion newRecordVersion = OVersionFactory.instance().createVersion();
      newRecordVersion.increment();
      newRecordVersion.increment();
      newRecordVersion.increment();
      newRecordVersion.increment();

      Assert
          .assertEquals(localPage.appendRecord(newRecordVersion, new byte[] { 2, 2, 2, 4, 5, 6, 5, 4, 2, 2, 2 }, false), position);

      int recordPageOffset = localPage.getRecordPageOffset(position);
      int recordSize = localPage.getRecordSize(position);
      Assert.assertEquals(recordSize, 11);

      Assert.assertEquals(localPage.getRecordVersion(position), newRecordVersion);
      Assert.assertEquals(localPage.getBinaryValue(recordPageOffset, recordSize), new byte[] { 2, 2, 2, 4, 5, 6, 5, 4, 2, 2, 2 });

      assertChangesTracking(localPage, pagePointer);
    } finally {
      directMemory.free(pagePointer);
    }
  }

  public void testDeleteAddEqualVersion() throws Exception {
    long pagePointer = directMemory.allocate(new byte[OLocalPage.PAGE_SIZE]);
    try {
      OLocalPage localPage = new OLocalPage(pagePointer, true, OLocalPage.TrackMode.BOTH);

      ORecordVersion recordVersion = OVersionFactory.instance().createVersion();
      recordVersion.increment();
      recordVersion.increment();

      int position = localPage.appendRecord(recordVersion, new byte[] { 1, 2, 3, 4, 5, 6, 5, 4, 3, 2, 1 }, false);

      Assert.assertTrue(localPage.deleteRecord(position));

      Assert.assertEquals(localPage.appendRecord(recordVersion, new byte[] { 2, 2, 2, 4, 5, 6, 5, 4, 2, 2, 2 }, false), position);

      int recordPageOffset = localPage.getRecordPageOffset(position);
      int recordSize = localPage.getRecordSize(position);
      Assert.assertEquals(recordSize, 11);

      recordVersion.increment();
      Assert.assertEquals(localPage.getRecordVersion(position), recordVersion);
      Assert.assertEquals(localPage.getBinaryValue(recordPageOffset, recordSize), new byte[] { 2, 2, 2, 4, 5, 6, 5, 4, 2, 2, 2 });

      assertChangesTracking(localPage, pagePointer);
    } finally {
      directMemory.free(pagePointer);
    }
  }

  public void testDeleteAddEqualVersionKeepTombstoneVersion() throws Exception {
    long pagePointer = directMemory.allocate(new byte[OLocalPage.PAGE_SIZE]);
    try {
      OLocalPage localPage = new OLocalPage(pagePointer, true, OLocalPage.TrackMode.BOTH);

      ORecordVersion recordVersion = OVersionFactory.instance().createVersion();
      recordVersion.increment();
      recordVersion.increment();

      int position = localPage.appendRecord(recordVersion, new byte[] { 1, 2, 3, 4, 5, 6, 5, 4, 3, 2, 1 }, false);

      Assert.assertTrue(localPage.deleteRecord(position));

      Assert.assertEquals(localPage.appendRecord(recordVersion, new byte[] { 2, 2, 2, 4, 5, 6, 5, 4, 2, 2, 2 }, true), position);

      int recordPageOffset = localPage.getRecordPageOffset(position);
      int recordSize = localPage.getRecordSize(position);
      Assert.assertEquals(recordSize, 11);

      Assert.assertEquals(localPage.getRecordVersion(position), recordVersion);
      Assert.assertEquals(localPage.getBinaryValue(recordPageOffset, recordSize), new byte[] { 2, 2, 2, 4, 5, 6, 5, 4, 2, 2, 2 });

      assertChangesTracking(localPage, pagePointer);
    } finally {
      directMemory.free(pagePointer);
    }
  }

  public void testDeleteTwoOutOfFour() throws Exception {
    long pagePointer = directMemory.allocate(new byte[OLocalPage.PAGE_SIZE]);
    try {
      OLocalPage localPage = new OLocalPage(pagePointer, true, OLocalPage.TrackMode.BOTH);

      ORecordVersion recordVersion = OVersionFactory.instance().createVersion();
      recordVersion.increment();

      int positionOne = localPage.appendRecord(recordVersion, new byte[] { 1, 2, 3, 4, 5, 6, 5, 4, 3, 2, 1 }, false);
      int positionTwo = localPage.appendRecord(recordVersion, new byte[] { 2, 2, 3, 4, 5, 6, 5, 4, 3, 2, 2 }, false);
      int positionThree = localPage.appendRecord(recordVersion, new byte[] { 3, 2, 3, 4, 5, 6, 5, 4, 3, 2, 3 }, false);
      int positionFour = localPage.appendRecord(recordVersion, new byte[] { 4, 2, 3, 4, 5, 6, 5, 4, 3, 2, 4 }, false);

      Assert.assertEquals(localPage.getRecordsCount(), 4);
      Assert.assertEquals(positionOne, 0);
      Assert.assertEquals(positionTwo, 1);
      Assert.assertEquals(positionThree, 2);
      Assert.assertEquals(positionFour, 3);

      Assert.assertFalse(localPage.isDeleted(0));
      Assert.assertFalse(localPage.isDeleted(1));
      Assert.assertFalse(localPage.isDeleted(2));
      Assert.assertFalse(localPage.isDeleted(3));

      int freeSpace = localPage.getFreeSpace();

      Assert.assertTrue(localPage.deleteRecord(0));
      Assert.assertTrue(localPage.deleteRecord(2));

      Assert.assertFalse(localPage.deleteRecord(0));
      Assert.assertFalse(localPage.deleteRecord(7));

      Assert.assertEquals(localPage.findFirstDeletedRecord(0), 0);
      Assert.assertEquals(localPage.findFirstDeletedRecord(1), 2);
      Assert.assertEquals(localPage.findFirstDeletedRecord(3), -1);

      int pageOffset = localPage.getRecordPageOffset(0);
      Assert.assertEquals(pageOffset, -1);
      Assert.assertEquals(localPage.getRecordSize(0), -1);
      Assert.assertEquals(localPage.getRecordVersion(0), recordVersion);

      pageOffset = localPage.getRecordPageOffset(1);
      Assert.assertEquals(localPage.getBinaryValue(pageOffset, 11), new byte[] { 2, 2, 3, 4, 5, 6, 5, 4, 3, 2, 2 });
      Assert.assertEquals(localPage.getRecordSize(1), 11);
      Assert.assertEquals(localPage.getRecordVersion(1), recordVersion);

      pageOffset = localPage.getRecordPageOffset(2);
      Assert.assertEquals(pageOffset, -1);
      Assert.assertEquals(localPage.getRecordSize(2), -1);
      Assert.assertEquals(localPage.getRecordVersion(2), recordVersion);

      pageOffset = localPage.getRecordPageOffset(3);
      Assert.assertEquals(localPage.getBinaryValue(pageOffset, 11), new byte[] { 4, 2, 3, 4, 5, 6, 5, 4, 3, 2, 4 });
      Assert.assertEquals(localPage.getRecordSize(3), 11);
      Assert.assertEquals(localPage.getRecordVersion(3), recordVersion);

      Assert.assertEquals(localPage.getRecordsCount(), 2);
      Assert.assertEquals(localPage.getFreeSpace(), freeSpace + 23 * 2);

      assertChangesTracking(localPage, pagePointer);
    } finally {
      directMemory.free(pagePointer);
    }
  }

  public void testAddFullPageDeleteAndAddAgain() throws Exception {
    long pagePointer = directMemory.allocate(new byte[OLocalPage.PAGE_SIZE]);
    try {
      OLocalPage localPage = new OLocalPage(pagePointer, true, OLocalPage.TrackMode.BOTH);

      Map<Integer, Byte> positionCounter = new HashMap<Integer, Byte>();
      Set<Integer> deletedPositions = new HashSet<Integer>();

      int lastPosition;
      byte counter = 0;
      int freeSpace = localPage.getFreeSpace();
      ORecordVersion recordVersion = OVersionFactory.instance().createVersion();
      recordVersion.increment();

      do {
        lastPosition = localPage.appendRecord(recordVersion, new byte[] { counter, counter, counter }, false);
        if (lastPosition >= 0) {
          Assert.assertEquals(lastPosition, positionCounter.size());
          positionCounter.put(lastPosition, counter);
          counter++;

          Assert.assertEquals(localPage.getFreeSpace(), freeSpace - (19 + OVersionFactory.instance().getVersionSize()));
          freeSpace = localPage.getFreeSpace();
        }
      } while (lastPosition >= 0);

      int filledRecordsCount = positionCounter.size();
      Assert.assertEquals(localPage.getRecordsCount(), filledRecordsCount);

      for (int i = 0; i < filledRecordsCount; i += 2) {
        localPage.deleteRecord(i);
        deletedPositions.add(i);
        positionCounter.remove(i);
      }

      freeSpace = localPage.getFreeSpace();
      do {
        lastPosition = localPage.appendRecord(recordVersion, new byte[] { counter, counter, counter }, false);
        if (lastPosition >= 0) {
          positionCounter.put(lastPosition, counter);
          counter++;

          Assert.assertEquals(localPage.getFreeSpace(), freeSpace - 15);
          freeSpace = localPage.getFreeSpace();
        }
      } while (lastPosition >= 0);

      ORecordVersion deletedVersion = OVersionFactory.instance().createVersion();
      deletedVersion.copyFrom(recordVersion);

      deletedVersion.increment();

      Assert.assertEquals(localPage.getRecordsCount(), filledRecordsCount);
      for (Map.Entry<Integer, Byte> entry : positionCounter.entrySet()) {
        int pageOffset = localPage.getRecordPageOffset(entry.getKey());

        Assert.assertEquals(localPage.getBinaryValue(pageOffset, 3),
            new byte[] { entry.getValue(), entry.getValue(), entry.getValue() });
        Assert.assertEquals(localPage.getRecordSize(entry.getKey()), 3);

        if (deletedPositions.contains(entry.getKey()))
          Assert.assertEquals(localPage.getRecordVersion(entry.getKey()), deletedVersion);
        else
          Assert.assertEquals(localPage.getRecordVersion(entry.getKey()), recordVersion);

      }

      assertChangesTracking(localPage, pagePointer);
    } finally {
      directMemory.free(pagePointer);
    }
  }

  public void testAddFullPageDeleteAndAddAgainWithoutDefragmentation() throws Exception {
    long pagePointer = directMemory.allocate(new byte[OLocalPage.PAGE_SIZE]);
    try {
      OLocalPage localPage = new OLocalPage(pagePointer, true, OLocalPage.TrackMode.BOTH);

      Map<Integer, Byte> positionCounter = new HashMap<Integer, Byte>();
      Set<Integer> deletedPositions = new HashSet<Integer>();

      int lastPosition;
      byte counter = 0;
      int freeSpace = localPage.getFreeSpace();
      ORecordVersion recordVersion = OVersionFactory.instance().createVersion();
      recordVersion.increment();

      do {
        lastPosition = localPage.appendRecord(recordVersion, new byte[] { counter, counter, counter }, false);
        if (lastPosition >= 0) {
          Assert.assertEquals(lastPosition, positionCounter.size());
          positionCounter.put(lastPosition, counter);
          counter++;

          Assert.assertEquals(localPage.getFreeSpace(), freeSpace - (19 + OVersionFactory.instance().getVersionSize()));
          freeSpace = localPage.getFreeSpace();
        }
      } while (lastPosition >= 0);

      int filledRecordsCount = positionCounter.size();
      Assert.assertEquals(localPage.getRecordsCount(), filledRecordsCount);

      for (int i = 0; i < filledRecordsCount; i += 2) {
        localPage.deleteRecord(i);
        deletedPositions.add(i);
        positionCounter.remove(i);
      }

      freeSpace = localPage.getFreeSpace();
      do {
        lastPosition = localPage.appendRecord(recordVersion, new byte[] { counter, counter, counter }, false);
        if (lastPosition >= 0) {
          positionCounter.put(lastPosition, counter);
          counter++;

          Assert.assertEquals(localPage.getFreeSpace(), freeSpace - 15);
          freeSpace = localPage.getFreeSpace();
        }
      } while (lastPosition >= 0);

      ORecordVersion deletedVersion = OVersionFactory.instance().createVersion();
      deletedVersion.copyFrom(recordVersion);

      deletedVersion.increment();

      Assert.assertEquals(localPage.getRecordsCount(), filledRecordsCount);
      for (Map.Entry<Integer, Byte> entry : positionCounter.entrySet()) {
        int pageOffset = localPage.getRecordPageOffset(entry.getKey());

        Assert.assertEquals(localPage.getBinaryValue(pageOffset, 3),
            new byte[] { entry.getValue(), entry.getValue(), entry.getValue() });
        Assert.assertEquals(localPage.getRecordSize(entry.getKey()), 3);

        if (deletedPositions.contains(entry.getKey()))
          Assert.assertEquals(localPage.getRecordVersion(entry.getKey()), deletedVersion);
        else
          Assert.assertEquals(localPage.getRecordVersion(entry.getKey()), recordVersion);

      }

      assertChangesTracking(localPage, pagePointer);
    } finally {
      directMemory.free(pagePointer);
    }
  }

  public void testAddBigRecordDeleteAndAddSmallRecords() throws Exception {
    long pagePointer = directMemory.allocate(new byte[OLocalPage.PAGE_SIZE]);
    try {
      OLocalPage localPage = new OLocalPage(pagePointer, true, OLocalPage.TrackMode.BOTH);

      ORecordVersion recordVersion = OVersionFactory.instance().createVersion();
      recordVersion.increment();
      recordVersion.increment();

      final byte[] bigChunk = new byte[OLocalPage.MAX_ENTRY_SIZE / 2];
      final MersenneTwisterFast mersenneTwisterFast = new MersenneTwisterFast();
      mersenneTwisterFast.nextBytes(bigChunk);

      int position = localPage.appendRecord(recordVersion, bigChunk, false);
      Assert.assertEquals(position, 0);
      Assert.assertEquals(localPage.getRecordVersion(0), recordVersion);

      Assert.assertTrue(localPage.deleteRecord(0));

      recordVersion.increment();
      int freeSpace = localPage.getFreeSpace();
      Map<Integer, Byte> positionCounter = new HashMap<Integer, Byte>();
      int lastPosition;
      byte counter = 0;
      do {
        lastPosition = localPage.appendRecord(recordVersion, new byte[] { counter, counter, counter }, false);
        if (lastPosition >= 0) {
          Assert.assertEquals(lastPosition, positionCounter.size());
          positionCounter.put(lastPosition, counter);
          counter++;

          if (lastPosition == 0)
            Assert.assertEquals(localPage.getFreeSpace(), freeSpace - 15);
          else
            Assert.assertEquals(localPage.getFreeSpace(), freeSpace - (19 + OVersionFactory.instance().getVersionSize()));

          freeSpace = localPage.getFreeSpace();
        }
      } while (lastPosition >= 0);

      Assert.assertEquals(localPage.getRecordsCount(), positionCounter.size());
      for (Map.Entry<Integer, Byte> entry : positionCounter.entrySet()) {
        int pageOffset = localPage.getRecordPageOffset(entry.getKey());

        Assert.assertEquals(localPage.getBinaryValue(pageOffset, 3),
            new byte[] { entry.getValue(), entry.getValue(), entry.getValue() });
        Assert.assertEquals(localPage.getRecordSize(entry.getKey()), 3);
        Assert.assertEquals(localPage.getRecordVersion(entry.getKey()), recordVersion);
      }

      assertChangesTracking(localPage, pagePointer);
    } finally {
      directMemory.free(pagePointer);
    }
  }

  public void testFindFirstRecord() throws Exception {
    long pagePointer = directMemory.allocate(new byte[OLocalPage.PAGE_SIZE]);
    final MersenneTwisterFast mersenneTwister = new MersenneTwisterFast();
    try {
      OLocalPage localPage = new OLocalPage(pagePointer, true, OLocalPage.TrackMode.BOTH);

      Set<Integer> positions = new HashSet<Integer>();

      int lastPosition;
      byte counter = 0;
      int freeSpace = localPage.getFreeSpace();

      ORecordVersion recordVersion = OVersionFactory.instance().createVersion();
      recordVersion.increment();

      do {
        lastPosition = localPage.appendRecord(recordVersion, new byte[] { counter, counter, counter }, false);
        if (lastPosition >= 0) {
          Assert.assertEquals(lastPosition, positions.size());
          positions.add(lastPosition);
          counter++;

          Assert.assertEquals(localPage.getFreeSpace(), freeSpace - (19 + OVersionFactory.instance().getVersionSize()));
          freeSpace = localPage.getFreeSpace();
        }
      } while (lastPosition >= 0);

      int filledRecordsCount = positions.size();
      Assert.assertEquals(localPage.getRecordsCount(), filledRecordsCount);

      for (int i = 0; i < filledRecordsCount; i++) {
        if (mersenneTwister.nextBoolean()) {
          localPage.deleteRecord(i);
          positions.remove(i);
        }
      }

      int recordsIterated = 0;
      int recordPosition = 0;
      int lastRecordPosition = -1;

      do {
        recordPosition = localPage.findFirstRecord(recordPosition);
        if (recordPosition < 0)
          break;

        Assert.assertTrue(positions.contains(recordPosition));
        Assert.assertTrue(recordPosition > lastRecordPosition);

        lastRecordPosition = recordPosition;

        recordPosition++;
        recordsIterated++;
      } while (recordPosition >= 0);

      Assert.assertEquals(recordsIterated, positions.size());

      assertChangesTracking(localPage, pagePointer);
    } finally {
      directMemory.free(pagePointer);
    }

  }

  public void testFindLastRecord() throws Exception {
    long pagePointer = directMemory.allocate(new byte[OLocalPage.PAGE_SIZE]);
    final MersenneTwisterFast mersenneTwister = new MersenneTwisterFast();
    try {
      OLocalPage localPage = new OLocalPage(pagePointer, true, OLocalPage.TrackMode.BOTH);

      Set<Integer> positions = new HashSet<Integer>();

      int lastPosition;
      byte counter = 0;
      int freeSpace = localPage.getFreeSpace();

      ORecordVersion recordVersion = OVersionFactory.instance().createVersion();
      recordVersion.increment();

      do {
        lastPosition = localPage.appendRecord(recordVersion, new byte[] { counter, counter, counter }, false);
        if (lastPosition >= 0) {
          Assert.assertEquals(lastPosition, positions.size());
          positions.add(lastPosition);
          counter++;

          Assert.assertEquals(localPage.getFreeSpace(), freeSpace - (19 + OVersionFactory.instance().getVersionSize()));
          freeSpace = localPage.getFreeSpace();
        }
      } while (lastPosition >= 0);

      int filledRecordsCount = positions.size();
      Assert.assertEquals(localPage.getRecordsCount(), filledRecordsCount);

      for (int i = 0; i < filledRecordsCount; i++) {
        if (mersenneTwister.nextBoolean()) {
          localPage.deleteRecord(i);
          positions.remove(i);
        }
      }

      int recordsIterated = 0;
      int recordPosition = Integer.MAX_VALUE;
      int lastRecordPosition = Integer.MAX_VALUE;
      do {
        recordPosition = localPage.findLastRecord(recordPosition);
        if (recordPosition < 0)
          break;

        Assert.assertTrue(positions.contains(recordPosition));
        Assert.assertTrue(recordPosition < lastRecordPosition);

        recordPosition--;
        recordsIterated++;
      } while (recordPosition >= 0);

      Assert.assertEquals(recordsIterated, positions.size());

      assertChangesTracking(localPage, pagePointer);
    } finally {
      directMemory.free(pagePointer);
    }
  }

  public void testSetGetNextPage() throws Exception {
    long pagePointer = directMemory.allocate(new byte[OLocalPage.PAGE_SIZE]);
    try {
      OLocalPage localPage = new OLocalPage(pagePointer, true, OLocalPage.TrackMode.BOTH);
      localPage.setNextPage(1034);
      Assert.assertEquals(localPage.getNextPage(), 1034);

      assertChangesTracking(localPage, pagePointer);
    } finally {
      directMemory.free(pagePointer);
    }
  }

  public void testSetGetPrevPage() throws Exception {
    long pagePointer = directMemory.allocate(new byte[OLocalPage.PAGE_SIZE]);
    try {
      OLocalPage localPage = new OLocalPage(pagePointer, true, OLocalPage.TrackMode.BOTH);
      localPage.setPrevPage(1034);
      Assert.assertEquals(localPage.getPrevPage(), 1034);

      assertChangesTracking(localPage, pagePointer);
    } finally {
      directMemory.free(pagePointer);
    }
  }

  public void testReplaceOneRecordWithBiggerSize() throws Exception {
    long pagePointer = directMemory.allocate(new byte[OLocalPage.PAGE_SIZE]);
    try {
      OLocalPage localPage = new OLocalPage(pagePointer, true, OLocalPage.TrackMode.BOTH);
      Assert.assertEquals(localPage.getRecordsCount(), 0);

      ORecordVersion recordVersion = OVersionFactory.instance().createVersion();
      recordVersion.increment();

      int index = localPage.appendRecord(recordVersion, new byte[] { 1, 2, 3, 4, 5, 6, 5, 4, 3, 2, 1 }, false);
      int freeSpace = localPage.getFreeSpace();

      ORecordVersion newRecordVersion = OVersionFactory.instance().createVersion();
      newRecordVersion.copyFrom(recordVersion);
      newRecordVersion.increment();

      int written = localPage.replaceRecord(index, new byte[] { 5, 2, 3, 4, 5, 11, 5, 4, 3, 2, 1, 3 }, newRecordVersion);
      Assert.assertEquals(localPage.getFreeSpace(), freeSpace);
      Assert.assertEquals(written, 11);

      Assert.assertEquals(localPage.getRecordSize(index), 11);

      int pageOffset = localPage.getRecordPageOffset(index);
      Assert.assertEquals(localPage.getBinaryValue(pageOffset, 11), new byte[] { 5, 2, 3, 4, 5, 11, 5, 4, 3, 2, 1 });

      Assert.assertEquals(localPage.getRecordVersion(index), newRecordVersion);

      assertChangesTracking(localPage, pagePointer);
    } finally {
      directMemory.free(pagePointer);
    }
  }

  public void testReplaceOneRecordWithEqualSize() throws Exception {
    long pagePointer = directMemory.allocate(new byte[OLocalPage.PAGE_SIZE]);
    try {
      OLocalPage localPage = new OLocalPage(pagePointer, true, OLocalPage.TrackMode.BOTH);
      Assert.assertEquals(localPage.getRecordsCount(), 0);

      ORecordVersion recordVersion = OVersionFactory.instance().createVersion();
      recordVersion.increment();

      int index = localPage.appendRecord(recordVersion, new byte[] { 1, 2, 3, 4, 5, 6, 5, 4, 3, 2, 1 }, false);
      int freeSpace = localPage.getFreeSpace();

      ORecordVersion newRecordVersion = OVersionFactory.instance().createVersion();
      newRecordVersion.copyFrom(recordVersion);
      newRecordVersion.increment();

      int written = localPage.replaceRecord(index, new byte[] { 5, 2, 3, 4, 5, 11, 5, 4, 3, 2, 1 }, newRecordVersion);
      Assert.assertEquals(localPage.getFreeSpace(), freeSpace);
      Assert.assertEquals(written, 11);

      Assert.assertEquals(localPage.getRecordSize(index), 11);

      int recordPageOffset = localPage.getRecordPageOffset(index);
      Assert.assertEquals(localPage.getBinaryValue(recordPageOffset, 11), new byte[] { 5, 2, 3, 4, 5, 11, 5, 4, 3, 2, 1 });

      Assert.assertEquals(localPage.getRecordVersion(index), newRecordVersion);

      assertChangesTracking(localPage, pagePointer);
    } finally {
      directMemory.free(pagePointer);
    }
  }

  public void testReplaceOneRecordWithSmallerSize() throws Exception {
    long pagePointer = directMemory.allocate(new byte[OLocalPage.PAGE_SIZE]);
    try {
      OLocalPage localPage = new OLocalPage(pagePointer, true, OLocalPage.TrackMode.BOTH);
      Assert.assertEquals(localPage.getRecordsCount(), 0);

      ORecordVersion recordVersion = OVersionFactory.instance().createVersion();
      recordVersion.increment();

      int index = localPage.appendRecord(recordVersion, new byte[] { 1, 2, 3, 4, 5, 6, 5, 4, 3, 2, 1 }, false);
      int freeSpace = localPage.getFreeSpace();

      ORecordVersion newRecordVersion = OVersionFactory.instance().createVersion();
      newRecordVersion.copyFrom(recordVersion);
      newRecordVersion.increment();

      int written = localPage.replaceRecord(index, new byte[] { 5, 2, 3, 4, 5, 11, }, newRecordVersion);
      Assert.assertEquals(localPage.getFreeSpace(), freeSpace);
      Assert.assertEquals(written, 6);

      Assert.assertEquals(localPage.getRecordSize(index), 6);

      int pageOffset = localPage.getRecordPageOffset(index);
      Assert.assertEquals(localPage.getBinaryValue(pageOffset, 6), new byte[] { 5, 2, 3, 4, 5, 11 });

      Assert.assertEquals(localPage.getRecordVersion(index), newRecordVersion);

      assertChangesTracking(localPage, pagePointer);
    } finally {
      directMemory.free(pagePointer);
    }
  }

  public void testReplaceOneRecordNoVersionUpdate() throws Exception {
    long pagePointer = directMemory.allocate(new byte[OLocalPage.PAGE_SIZE]);
    try {
      OLocalPage localPage = new OLocalPage(pagePointer, true, OLocalPage.TrackMode.BOTH);
      Assert.assertEquals(localPage.getRecordsCount(), 0);

      ORecordVersion recordVersion = OVersionFactory.instance().createVersion();
      recordVersion.increment();

      int index = localPage.appendRecord(recordVersion, new byte[] { 1, 2, 3, 4, 5, 6, 5, 4, 3, 2, 1 }, false);
      int freeSpace = localPage.getFreeSpace();

      ORecordVersion newRecordVersion = OVersionFactory.instance().createVersion();
      newRecordVersion.copyFrom(recordVersion);
      newRecordVersion.increment();

      int written = localPage.replaceRecord(index, new byte[] { 5, 2, 3, 4, 5, 11, 5, 4, 3, 2, 1, 3 }, null);
      Assert.assertEquals(localPage.getFreeSpace(), freeSpace);
      Assert.assertEquals(written, 11);

      Assert.assertEquals(localPage.getRecordSize(index), 11);

      int recordPageOffset = localPage.getRecordPageOffset(index);
      Assert.assertEquals(localPage.getBinaryValue(recordPageOffset, 11), new byte[] { 5, 2, 3, 4, 5, 11, 5, 4, 3, 2, 1 });

      Assert.assertEquals(localPage.getRecordVersion(index), recordVersion);

      assertChangesTracking(localPage, pagePointer);
    } finally {
      directMemory.free(pagePointer);
    }
  }

  public void testReplaceOneRecordLowerVersion() throws Exception {
    long pagePointer = directMemory.allocate(new byte[OLocalPage.PAGE_SIZE]);
    try {
      OLocalPage localPage = new OLocalPage(pagePointer, true, OLocalPage.TrackMode.BOTH);
      Assert.assertEquals(localPage.getRecordsCount(), 0);

      ORecordVersion recordVersion = OVersionFactory.instance().createVersion();
      recordVersion.increment();

      int index = localPage.appendRecord(recordVersion, new byte[] { 1, 2, 3, 4, 5, 6, 5, 4, 3, 2, 1 }, false);
      int freeSpace = localPage.getFreeSpace();

      ORecordVersion newRecordVersion = OVersionFactory.instance().createVersion();
      newRecordVersion.copyFrom(recordVersion);

      int written = localPage.replaceRecord(index, new byte[] { 5, 2, 3, 4, 5, 11, 5, 4, 3, 2, 1, 3 }, newRecordVersion);
      Assert.assertEquals(localPage.getFreeSpace(), freeSpace);
      Assert.assertEquals(written, 11);

      Assert.assertEquals(localPage.getRecordSize(index), 11);

      int recordPageOffset = localPage.getRecordPageOffset(index);
      Assert.assertEquals(localPage.getBinaryValue(recordPageOffset, 11), new byte[] { 5, 2, 3, 4, 5, 11, 5, 4, 3, 2, 1 });

      Assert.assertEquals(localPage.getRecordVersion(index), recordVersion);

      assertChangesTracking(localPage, pagePointer);
    } finally {
      directMemory.free(pagePointer);
    }
  }

  private void assertChangesTracking(OLocalPage localPage, long pagePointer) throws IOException {
    long restoredPagePointer = directMemory.allocate(new byte[OLocalPage.PAGE_SIZE]);
    try {
      OLocalPage restoredPage = new OLocalPage(restoredPagePointer, false, OLocalPage.TrackMode.BOTH);

      restoredPage.restoreChanges(localPage.getPageChanges());

      Assert.assertEquals(directMemory.get(restoredPagePointer + SYSTEM_OFFSET, OLocalPage.PAGE_SIZE - SYSTEM_OFFSET),
          directMemory.get(pagePointer + SYSTEM_OFFSET, OLocalPage.PAGE_SIZE - SYSTEM_OFFSET));

      List<OFullPageDiff<?>> changes = new ArrayList<OFullPageDiff<?>>();
      for (OPageDiff<?> pageDiff : localPage.getPageChanges())
        changes.add((OFullPageDiff<?>) pageDiff);

      restoredPage.revertChanges(changes);

      Assert.assertEquals(directMemory.get(restoredPagePointer + SYSTEM_OFFSET, OLocalPage.PAGE_SIZE - SYSTEM_OFFSET),
          new byte[OLocalPage.PAGE_SIZE - SYSTEM_OFFSET]);
    } finally {
      directMemory.free(restoredPagePointer);
    }
  }
}
