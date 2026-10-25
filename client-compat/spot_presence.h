// Same physical room, including its private instance. Author: 최병호
#pragma once
namespace logh {
inline bool spotOwnerPresent(unsigned owner, unsigned spot, unsigned memberId,
    unsigned memberSpot, unsigned memberOwner) {
    return owner && spot && memberId == owner && memberSpot == spot && memberOwner == owner;
}
inline bool sameSpotOccupant(unsigned ownId, unsigned spot, unsigned owner,
    unsigned peerId, unsigned peerSpot, unsigned peerOwner) {
    // Spot zero denotes each captain's ship, not a shared public room.
    return ownId && peerId && ownId != peerId && spot &&
        spot == peerSpot && owner == peerOwner;
}
}
