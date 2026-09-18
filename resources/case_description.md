# AGENT ON A LEASH 
## Viseca 
Viseca is a leading Swiss fintech specializing in payment cards and cashless payment services. Through Viseca Card Services, it is one of Switzerland's largest issuers of Visa and Mastercard credit cards for banks and cobranding partners. Viseca Payment Services provides the technology and operational services behind card payments, including transaction processing, customer service, and fraud prevention. Its award-winning "one" digital service gives customers convenient control over their cards and spending. Founded in 1999 and wholly owned by Swiss retail and cantonal banks, Viseca combines decades of payment expertise with a focus on making payments simple, secure, and convenient.


## Description 
Imagine an Al shopping assistant that can buy things with your credit card. Such agents are already a reality, enabled by new capabilities from Visa and Mastercard. You ask it: "Buy me black running shoes for up to CHF 200." But how do you stay in control? How do you ensure that your Al shopping assistant:
• doesn't spend too much?
• doesn't buy from shady merchants?
• purchases what you actually intended?
• remains resilient against prompt injection attacks?
Your challenge is to build the trust and control layer that decides whether an Al agent may spend a customer's money.

Objective: Build a prototype wallet control layer that decides whether an Al shopping agent may spend a customer's money. The wallet control layer is configured through a customer-managed wallet policy and operates independently of the Al shopping agent and its shopping instructions.

## Deep Dive 
System overview: Customer (card owner) sends a shopping request to the Al shopping agent and separately defines the wallet policy directly with Wallet Control. The Al shopping agent forwards the proposed transaction, together with payment context, to Wallet Control. Wallet Control evaluates the transaction against the wallet policy and returns a decision, approve, decline, or ask, leading to the final outcome. (See attached architecture diagram in Github Repo.) Solution must be able to: Translate the customer's input (for example rules or a natural-language wallet policy) into clear, executable permissions. The customer must also be able to tighten, update, or revoke the wallet policy. Evaluate each proposed transaction and return to one of three decisions: approve, decline or ask the customer (step_up)

You build the wallet control, not the shopping agent. The prototype should work with the supplied synthetic data and simulator, respond within the required deadline, and remain predictable if optional models or external services fail. Do not hard-code decisions to scenario names, request IDs, or sequence positions.
Demonstrate:
1. One ordinary transaction completed with minimal friction.
2.One ambiguous, unsafe, or manipulated transaction receiving a useful intervention.
2. The human approval, rejection, or revocation path.
Judges should be able to understand what the system permitted, what evidence it considered, why it acted, and how the customer retained control.

Technical Preferences: Viseca ultimately intends to integrate control-layer configuration into the existing Viseca one mobile app, while the decision (approve/ decline/ask) must meet strict latency requirements and runs in the backend. We therefore recommend decoupling the wallet-control user interface from the engine that approves, blocks, or escalates transactions, allowing each component to be integrated, deployed, and scaled independently. If language models are used in the decision path, smaller, lower-latency models are preferred.
Support for Hackers:
• Synthetic data pack provided (Github)
• A live API with transactions is available during the hackathon
Combine different disciplines: rules, behavioural signals, machine learning, language models, interface design, or a thoughtful combination of them.

## Judging Criteria 
1. User Centricity - 25% Does the solution solve a real need in an understandable, usable way?
2. Security & Transparency - 25% Resistance of the policy logic against misuse and circumvention, combined with traceability of decisions (Approve/Decline/Step-up).
3. Innovation - 20% Creativity and novelty of the solution approach.
4. Feasibility - 15% Technical feasibility of the solution.
5. Viability - 15% Sustainable business model, cost-benefit ratio, realistic path to adoption.



## Github Repository with Details 
https://github.com/START-Hack/viseca-2026