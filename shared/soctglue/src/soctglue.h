#pragma once

/**
 * Add a message to the setup log. The setup log is a list of messages that are printed at the end of the setup phase, before entering main. This can be used by handlers to report errors during setup, such as DTB parsing errors.
 * @param msg A message to add to the setup log.
 */
void soct_add_setup_msg(const char *msg);
