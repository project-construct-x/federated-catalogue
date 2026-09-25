package eu.xfsc.fc.core.service.oid4vp;

import org.springframework.stereotype.Service;

@Service
public class ConnectorBindingChallengeService {

    public Challenge issue(String connectorDid) {
        // generate challenge, store in DB, return object
        return null;
    }

    public boolean consume(String challengeId, String connectorDid) {
        // validate and invalidate challenge
        return true;
    }
}
