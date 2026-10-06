# Security

MT Business is beta software. If you find a weakness in the application, in the nodes it talks to or in its confirmation
service, please write to contact@montana.quest rather than opening a public issue, and give us a reasonable time to close it
before publishing.

What is in scope:

- anything that lets a third party read, alter or forge a message or a call;
- anything that reveals who talks to whom, or when, to someone other than the parties;
- anything that lets one device impersonate another identity;
- anything that confirms a phone number or an e-mail address to someone who does not hold it, or that reads the directory
  beyond the numbers the asker already holds.

What is not a finding on its own:

- a dropped or redirected call or message that a party can detect;
- rate limiting, missing headers or version disclosure on the public web site.
