package com.leash.policy;

import java.util.ArrayList;
import java.util.List;

/** Our copy of a customer's wallet policy (the Viseca API calls it a mandate). Stored in store/policies.json. */
public class Policy {
    public String id;
    /** draft | active | revoked */
    public String status;
    public String instruction;
    public List<Rule> hardRules = new ArrayList<>();
    /** ask | decline | approve */
    public String uncertaintyPolicy = "ask";
    public List<String> guidance = new ArrayList<>();
    public List<String> openQuestions = new ArrayList<>();
    public String createdAt;
    public String confirmedAt;
    public String revokedAt;
    public String visecaDraftId;
    public String visecaMandateId;
}
